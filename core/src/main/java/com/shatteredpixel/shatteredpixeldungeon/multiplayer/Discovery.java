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

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;

//Automatic LAN discovery over UDP broadcast.
//Hosts announce themselves periodically and also answer queries, guests listen and query.
//Doing both directions makes discovery work on networks that filter broadcasts one way.
public class Discovery {

	public static final int UDP_PORT = 45580;

	private static final String MAGIC = "SPDMP";
	private static final String QUERY = "SPDMP?";

	private static final long ROOM_EXPIRY = 4500;

	//identifies a hosting device, so a host reachable through several networks is only listed once
	private static final String HOST_ID = Long.toHexString(new java.util.Random().nextLong() & 0xFFFFFFFFFFFFL);

	public static class Room {
		public String name;
		public String address;
		public int port;
		public int version;
		public boolean open;
		String id;
		long lastSeen;
	}

	// ***** Host side *****

	private static volatile Thread hostThread;
	private static volatile DatagramSocket hostSocket;
	private static volatile String hostName = "";
	private static volatile boolean hostOpen = true;

	public static synchronized void startHosting( String name ){
		hostName = name;
		hostOpen = true;
		if (hostThread != null) return;
		lockRef(true);
		hostThread = new Thread(){
			@Override
			public void run() {
				DatagramSocket socket = null;
				try {
					socket = new DatagramSocket(null);
					socket.setReuseAddress(true);
					socket.setBroadcast(true);
					try {
						socket.bind(new InetSocketAddress(UDP_PORT));
					} catch (Exception e){
						//could not bind the shared port, we can still announce from any port
						socket.close();
						socket = new DatagramSocket();
						socket.setBroadcast(true);
					}
					socket.setSoTimeout(250);
					hostSocket = socket;
					long nextAnnounce = 0;
					byte[] buf = new byte[512];
					while (hostThread == this){
						long now = System.currentTimeMillis();
						if (now >= nextAnnounce){
							nextAnnounce = now + 1000;
							byte[] msg = announcement();
							for (InetAddress a : broadcastAddresses()){
								try {
									socket.send(new DatagramPacket(msg, msg.length, a, UDP_PORT));
								} catch (Exception e){
									//some interfaces may refuse broadcasts, ignore
								}
							}
						}
						try {
							DatagramPacket p = new DatagramPacket(buf, buf.length);
							socket.receive(p);
							String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
							if (s.startsWith(QUERY)){
								byte[] msg = announcement();
								socket.send(new DatagramPacket(msg, msg.length, p.getAddress(), p.getPort()));
							}
						} catch (SocketTimeoutException e){
							//expected
						}
					}
				} catch (Exception e){
					if (hostThread == this) Game.reportException(e);
				} finally {
					if (socket != null) socket.close();
				}
			}
		};
		hostThread.setName("MP announce");
		hostThread.setDaemon(true);
		hostThread.start();
	}

	public static void setHostOpen( boolean open ){
		hostOpen = open;
	}

	public static void setHostName( String name ){
		hostName = name;
	}

	private static byte[] announcement(){
		String s = MAGIC + "|" + MP.PROTOCOL + "|" + Game.versionCode + "|" + Net.TCP_PORT + "|" + (hostOpen ? 1 : 0) + "|" + HOST_ID + "|" + hostName;
		return s.getBytes(StandardCharsets.UTF_8);
	}

	public static synchronized void stopHosting(){
		if (hostThread != null){
			hostThread = null;
			lockRef(false);
		}
		DatagramSocket s = hostSocket;
		hostSocket = null;
		if (s != null) s.close();
	}

	// ***** Guest side *****

	private static final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
	private static volatile Thread listenThread;
	private static volatile Thread queryThread;
	private static volatile DatagramSocket listenSocket;
	private static volatile DatagramSocket querySocket;

	public static synchronized void startBrowsing(){
		if (listenThread != null) return;
		rooms.clear();
		lockRef(true);

		listenThread = new Thread(){
			@Override
			public void run() {
				DatagramSocket socket = null;
				try {
					socket = new DatagramSocket(null);
					socket.setReuseAddress(true);
					socket.setBroadcast(true);
					socket.bind(new InetSocketAddress(UDP_PORT));
					socket.setSoTimeout(500);
					listenSocket = socket;
					byte[] buf = new byte[512];
					while (listenThread == this){
						try {
							DatagramPacket p = new DatagramPacket(buf, buf.length);
							socket.receive(p);
							handlePacket(p);
						} catch (SocketTimeoutException e){
							//expected
						}
					}
				} catch (Exception e){
					//port may be unavailable, the query socket still receives replies
				} finally {
					if (socket != null) socket.close();
				}
			}
		};
		listenThread.setName("MP listen");
		listenThread.setDaemon(true);
		listenThread.start();

		queryThread = new Thread(){
			@Override
			public void run() {
				DatagramSocket socket = null;
				try {
					socket = new DatagramSocket();
					socket.setBroadcast(true);
					socket.setSoTimeout(250);
					querySocket = socket;
					byte[] q = (QUERY + "|" + MP.PROTOCOL).getBytes(StandardCharsets.UTF_8);
					long nextQuery = 0;
					byte[] buf = new byte[512];
					while (queryThread == this){
						long now = System.currentTimeMillis();
						if (now >= nextQuery){
							nextQuery = now + 1000;
							for (InetAddress a : broadcastAddresses()){
								try {
									socket.send(new DatagramPacket(q, q.length, a, UDP_PORT));
								} catch (Exception e){
									//ignore
								}
							}
						}
						try {
							DatagramPacket p = new DatagramPacket(buf, buf.length);
							socket.receive(p);
							handlePacket(p);
						} catch (SocketTimeoutException e){
							//expected
						}
					}
				} catch (Exception e){
					if (queryThread == this) Game.reportException(e);
				} finally {
					if (socket != null) socket.close();
				}
			}
		};
		queryThread.setName("MP query");
		queryThread.setDaemon(true);
		queryThread.start();
	}

	private static void handlePacket( DatagramPacket p ){
		String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
		if (!s.startsWith(MAGIC + "|")) return;
		String[] parts = s.split("\\|", 7);
		if (parts.length < 7) return;
		try {
			Room r = new Room();
			int proto = Integer.parseInt(parts[1]);
			if (proto != MP.PROTOCOL) return;
			r.version = Integer.parseInt(parts[2]);
			r.port = Integer.parseInt(parts[3]);
			r.open = parts[4].equals("1");
			r.id = parts[5];
			r.name = parts[6];
			r.address = p.getAddress().getHostAddress();
			r.lastSeen = System.currentTimeMillis();
			synchronized (rooms){
				Room existing = rooms.get(r.id);
				//keep the most useful address if the host is seen through several networks
				if (existing != null && fresh(existing) && addressRank(existing.address) < addressRank(r.address)){
					existing.lastSeen = r.lastSeen;
					existing.open = r.open;
					existing.name = r.name;
				} else {
					rooms.put(r.id, r);
				}
			}
		} catch (NumberFormatException e){
			//malformed packet, ignore
		}
	}

	public static synchronized void stopBrowsing(){
		if (listenThread != null || queryThread != null){
			listenThread = null;
			queryThread = null;
			lockRef(false);
		}
		DatagramSocket a = listenSocket, b = querySocket;
		listenSocket = querySocket = null;
		if (a != null) a.close();
		if (b != null) b.close();
	}

	private static boolean fresh( Room r ){
		return System.currentTimeMillis() - r.lastSeen < ROOM_EXPIRY;
	}

	//lower is better: typical home networks first, loopback last
	private static int addressRank( String a ){
		if (a.startsWith("192.168."))   return 0;
		if (a.startsWith("10."))        return 1;
		if (a.startsWith("172."))       return 2;
		if (a.startsWith("127."))       return 9;
		return 5;
	}

	public static ArrayList<Room> rooms(){
		long now = System.currentTimeMillis();
		ArrayList<Room> result = new ArrayList<>();
		for (String key : rooms.keySet()){
			Room r = rooms.get(key);
			if (r == null) continue;
			if (now - r.lastSeen > ROOM_EXPIRY){
				rooms.remove(key);
			} else {
				result.add(r);
			}
		}
		Collections.sort(result, new Comparator<Room>() {
			@Override
			public int compare(Room a, Room b) {
				return a.address.compareTo(b.address);
			}
		});
		return result;
	}

	// ***** Utility *****

	private static int lockCount = 0;

	private static synchronized void lockRef( boolean add ){
		lockCount += add ? 1 : -1;
		if (lockCount < 0) lockCount = 0;
		try {
			Game.platform.setMulticastLock(lockCount > 0);
		} catch (Exception e){
			Game.reportException(e);
		}
	}

	private static ArrayList<InetAddress> broadcastAddresses(){
		LinkedHashSet<InetAddress> result = new LinkedHashSet<>();
		try {
			result.add(InetAddress.getByName("255.255.255.255"));
		} catch (Exception e){
			//ignore
		}
		try {
			Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
			while (ifs != null && ifs.hasMoreElements()){
				NetworkInterface ni = ifs.nextElement();
				try {
					if (!ni.isUp() || ni.isLoopback()) continue;
				} catch (Exception e){
					continue;
				}
				for (InterfaceAddress ia : ni.getInterfaceAddresses()){
					if (ia.getBroadcast() != null){
						result.add(ia.getBroadcast());
					}
				}
			}
		} catch (Exception e){
			//ignore
		}
		//also try loopback, allows two copies of the game on one machine to find each other
		try {
			result.add(InetAddress.getByName("127.0.0.1"));
		} catch (Exception e){
			//ignore
		}
		return new ArrayList<>(result);
	}

	//IPv4 addresses of this device on the local network, for display
	public static ArrayList<String> localAddresses(){
		ArrayList<String> result = new ArrayList<>();
		try {
			Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
			while (ifs != null && ifs.hasMoreElements()){
				NetworkInterface ni = ifs.nextElement();
				try {
					if (!ni.isUp() || ni.isLoopback()) continue;
				} catch (Exception e){
					continue;
				}
				String n = (ni.getDisplayName() + " " + ni.getName()).toLowerCase(java.util.Locale.ROOT);
				boolean virtual = n.contains("virtual") || n.contains("vethernet") || n.contains("vmware")
						|| n.contains("vbox") || n.contains("hyper-v") || n.contains("wsl") || n.contains("docker");
				Enumeration<InetAddress> addrs = ni.getInetAddresses();
				while (addrs.hasMoreElements()){
					InetAddress a = addrs.nextElement();
					if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()){
						result.add((virtual ? "~" : "") + a.getHostAddress());
					}
				}
			}
		} catch (Exception e){
			//ignore
		}
		//real network adapters and common home ranges first
		Collections.sort(result, new Comparator<String>() {
			@Override
			public int compare(String a, String b) {
				boolean va = a.startsWith("~"), vb = b.startsWith("~");
				if (va != vb) return va ? 1 : -1;
				return addressRank(a.replace("~", "")) - addressRank(b.replace("~", ""));
			}
		});
		for (int i = 0; i < result.size(); i++){
			result.set(i, result.get(i).replace("~", ""));
		}
		return result;
	}
}
