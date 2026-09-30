/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.multiplayer;

import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

//Low level TCP transport between the two players.
//All socket work happens on background threads, the render thread only reads from a queue.
public class Net {

	public static final int TCP_PORT = 45581;

	private static final int MAX_FRAME = 64 * 1024 * 1024;
	private static final int PING_INTERVAL = 2000;
	private static final int TIMEOUT = 20000;
	private static final int CONNECT_TIMEOUT = 6000;

	//pseudo message types, generated locally
	public static final byte EV_CONNECTED       = 100;
	public static final byte EV_DISCONNECTED    = 101;
	public static final byte EV_CONNECT_FAILED  = 102;

	public static class Frame {
		public final byte type;
		public final byte[] data;

		Frame( byte type, byte[] data ){
			this.type = type;
			this.data = data;
		}

		public Bundle bundle(){
			if (data == null || data.length == 0) return new Bundle();
			try {
				return Bundle.fromString(decode(data));
			} catch (IOException e){
				Game.reportException(e);
				return new Bundle();
			}
		}
	}

	private static class OutFrame {
		final byte type;
		final byte[] data;
		final boolean compress;
		final Connection conn;

		OutFrame( byte type, byte[] data, boolean compress, Connection conn ){
			this.type = type;
			this.data = data;
			this.compress = compress;
			this.conn = conn;
		}
	}

	private static final ConcurrentLinkedQueue<Frame> incoming = new ConcurrentLinkedQueue<>();

	private static volatile Connection conn;
	private static volatile ServerSocket server;

	public static synchronized boolean hosting(){
		return server != null;
	}

	public static boolean connected(){
		Connection c = conn;
		return c != null && !c.closed;
	}

	public static Frame poll(){
		return incoming.poll();
	}

	public static void clearIncoming(){
		incoming.clear();
	}

	// ***** Host side *****

	public static synchronized boolean startServer(){
		if (server != null) return true;
		try {
			final ServerSocket ss = new ServerSocket();
			ss.setReuseAddress(true);
			ss.bind(new InetSocketAddress(TCP_PORT));
			server = ss;
		} catch (IOException e){
			Game.reportException(e);
			return false;
		}
		final ServerSocket ss = server;
		Thread accept = new Thread(){
			@Override
			public void run() {
				while (!ss.isClosed()){
					try {
						Socket s = ss.accept();
						Connection existing = conn;
						if (existing != null && !existing.closed){
							//only one guest at a time
							rejectBusy(s);
						} else {
							Connection c = new Connection(s);
							conn = c;
							incoming.add(new Frame(EV_CONNECTED, null));
							c.start();
						}
					} catch (IOException e){
						//socket closed or accept failed, loop checks for closure
					}
				}
			}
		};
		accept.setName("MP accept");
		accept.setDaemon(true);
		accept.start();
		return true;
	}

	private static void rejectBusy( final Socket s ){
		Thread t = new Thread(){
			@Override
			public void run() {
				try {
					s.setSoTimeout(3000);
					DataOutputStream out = new DataOutputStream(s.getOutputStream());
					Bundle b = new Bundle();
					b.put("ok", false);
					b.put("reason", "full");
					writeFrame(out, MP.MSG_WELCOME, b.toString().getBytes(StandardCharsets.UTF_8), false);
					out.flush();
					Thread.sleep(300);
				} catch (Exception e){
					//do nothing
				} finally {
					try { s.close(); } catch (IOException e) { /* ignore */ }
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	public static synchronized void stopServer(){
		if (server != null){
			try {
				server.close();
			} catch (IOException e){
				//ignore
			}
			server = null;
		}
	}

	// ***** Client side *****

	public static void connect( final String address, final int port ){
		disconnect();
		Thread t = new Thread(){
			@Override
			public void run() {
				Socket s = new Socket();
				try {
					s.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT);
					Connection c = new Connection(s);
					conn = c;
					incoming.add(new Frame(EV_CONNECTED, null));
					c.start();
				} catch (Exception e){
					try { s.close(); } catch (IOException e2) { /* ignore */ }
					incoming.add(new Frame(EV_CONNECT_FAILED, null));
				}
			}
		};
		t.setName("MP connect");
		t.setDaemon(true);
		t.start();
	}

	// ***** Both *****

	public static void send( byte type ){
		send(type, (byte[])null, false);
	}

	public static void send( byte type, Bundle bundle ){
		send(type, bundle, false);
	}

	public static void send( byte type, Bundle bundle, boolean compress ){
		send(type, bundle.toString().getBytes(StandardCharsets.UTF_8), compress);
	}

	public static void send( byte type, byte[] data, boolean compress ){
		Connection c = conn;
		if (c != null && !c.closed){
			c.out.add(new OutFrame(type, data == null ? new byte[0] : data, compress, c));
		}
	}

	//sends anything still queued, then closes. Used when leaving gracefully.
	public static void disconnectAfterFlush(){
		final Connection c = conn;
		conn = null;
		if (c != null){
			c.closeAfterFlush();
		}
	}

	public static void disconnect(){
		Connection c = conn;
		conn = null;
		if (c != null){
			c.close(false);
		}
	}

	public static void shutdown(){
		stopServer();
		disconnect();
		clearIncoming();
	}

	public static String decode( byte[] data ){
		try {
			if (data.length >= 2 && data[0] == (byte)0x1f && data[1] == (byte)0x8b){
				InputStream in = new GZIPInputStream(new ByteArrayInputStream(data), 8192);
				ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 4);
				byte[] buf = new byte[8192];
				int r;
				while ((r = in.read(buf)) != -1){
					bos.write(buf, 0, r);
				}
				in.close();
				return new String(bos.toByteArray(), StandardCharsets.UTF_8);
			} else {
				return new String(data, StandardCharsets.UTF_8);
			}
		} catch (IOException e){
			Game.reportException(e);
			return "{}";
		}
	}

	private static byte[] gzip( byte[] data ) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length / 4));
		GZIPOutputStream gz = new GZIPOutputStream(bos, 8192);
		gz.write(data);
		gz.close();
		return bos.toByteArray();
	}

	private static void writeFrame( DataOutputStream out, byte type, byte[] data, boolean compress ) throws IOException {
		byte[] payload = compress ? gzip(data) : data;
		out.writeByte(type);
		out.writeInt(payload.length);
		out.write(payload);
	}

	private static class Connection {

		final Socket socket;
		final LinkedBlockingQueue<OutFrame> out = new LinkedBlockingQueue<>();
		volatile boolean closed = false;
		volatile boolean flushThenClose = false;

		private DataInputStream din;
		private DataOutputStream dout;

		Connection( Socket s ) throws IOException {
			socket = s;
			socket.setTcpNoDelay(true);
			socket.setKeepAlive(true);
			socket.setSoTimeout(TIMEOUT);
			din = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
			dout = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
		}

		void start(){
			Thread reader = new Thread(){
				@Override
				public void run() {
					try {
						while (!closed){
							byte type = din.readByte();
							int len = din.readInt();
							if (len < 0 || len > MAX_FRAME){
								throw new IOException("bad frame size " + len);
							}
							byte[] data = new byte[len];
							din.readFully(data);
							if (type == MP.MSG_PING) continue;
							incoming.add(new Frame(type, data));
						}
					} catch (Exception e){
						//connection lost or closed
					}
					close(true);
				}
			};
			reader.setName("MP reader");
			reader.setDaemon(true);
			reader.start();

			Thread writer = new Thread(){
				@Override
				public void run() {
					try {
						while (!closed){
							OutFrame f = out.poll(PING_INTERVAL, TimeUnit.MILLISECONDS);
							if (closed) break;
							if (f == null){
								if (flushThenClose) break;
								writeFrame(dout, MP.MSG_PING, new byte[0], false);
							} else {
								writeFrame(dout, f.type, f.data, f.compress);
							}
							if (out.isEmpty()){
								dout.flush();
							}
							if (flushThenClose && out.isEmpty()){
								break;
							}
						}
						dout.flush();
					} catch (Exception e){
						//connection lost or closed
					}
					if (flushThenClose){
						try { Thread.sleep(200); } catch (InterruptedException e) { /* ignore */ }
						close(false);
					} else {
						close(true);
					}
				}
			};
			writer.setName("MP writer");
			writer.setDaemon(true);
			writer.start();
		}

		void closeAfterFlush(){
			flushThenClose = true;
			//wakes the writer thread
			out.add(new OutFrame(MP.MSG_PING, new byte[0], false, this));
		}

		synchronized void close( boolean notify ){
			if (closed) return;
			closed = true;
			try {
				socket.close();
			} catch (IOException e){
				//ignore
			}
			//only report the loss if this is still the active connection
			if (notify && conn == this){
				conn = null;
				incoming.add(new Frame(EV_DISCONNECTED, null));
			} else if (conn == this){
				conn = null;
			}
		}
	}
}
