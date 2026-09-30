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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndMessage;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import com.watabou.utils.Bundle;
import com.watabou.utils.FileUtils;

//Central state of an online (LAN) session: lobby, roles and message routing.
//Everything in here runs on the render thread unless noted otherwise.
public class MP {

	public static final int PROTOCOL = 1;

	//save slot used by the hero device for online games, never listed with regular games
	public static final int SLOT = 100;

	// ***** message types *****
	public static final byte MSG_PING       = 0;
	public static final byte MSG_HELLO      = 1;
	public static final byte MSG_WELCOME    = 2;
	public static final byte MSG_LOBBY      = 3;
	public static final byte MSG_READY      = 4;
	public static final byte MSG_CLASS      = 5;
	public static final byte MSG_START_REQ  = 6;
	public static final byte MSG_START      = 7;
	public static final byte MSG_SWAP_REQ   = 8;
	public static final byte MSG_SWAP_ANS   = 9;
	public static final byte MSG_LEVEL      = 10;
	public static final byte MSG_SNAP       = 11;
	public static final byte MSG_CMD        = 12;
	public static final byte MSG_NOTICE     = 13;
	public static final byte MSG_GAME_OVER  = 14;
	public static final byte MSG_HANDOVER   = 15;
	public static final byte MSG_TO_LOBBY   = 16;
	public static final byte MSG_BYE        = 17;
	public static final byte MSG_RESYNC     = 18;
	public static final byte MSG_STATE      = 19;
	public static final byte MSG_NAME       = 20;

	public enum Role {
		HERO, DM
	}

	public static class Player {
		public String name = "";
		public Role role = Role.HERO;
		public HeroClass cls = null;
		public boolean ready = false;
		public boolean present = false;
	}

	//0 is always the host (tcp server), 1 is always the guest
	public static final Player[] players = new Player[]{ new Player(), new Player() };

	private static boolean session = false;
	private static boolean isHost = false;
	private static boolean welcomed = false;
	private static boolean inGame = false;
	private static boolean gameFinished = false;

	//cached for hot paths, read from the actor thread too
	public static volatile boolean authority = false;
	public static volatile boolean mirror = false;

	//incremented whenever the lobby changes, so UI can refresh cheaply
	public static int lobbyVersion = 0;

	private static boolean swapRequested = false;
	private static WndOptions swapPrompt = null;

	// ***** state queries *****

	public static boolean sessionActive(){
		return session;
	}

	public static boolean isHost(){
		return isHost;
	}

	public static boolean inGame(){
		return inGame;
	}

	public static boolean gameFinished(){
		return gameFinished;
	}

	public static int mySlot(){
		return isHost ? 0 : 1;
	}

	public static int peerSlot(){
		return isHost ? 1 : 0;
	}

	public static Player me(){
		return players[mySlot()];
	}

	public static Player peer(){
		return players[peerSlot()];
	}

	public static Role myRole(){
		return me().role;
	}

	public static boolean peerPresent(){
		return peer().present;
	}

	public static Player heroPlayer(){
		return players[0].role == Role.HERO ? players[0] : players[1];
	}

	public static Player dmPlayer(){
		return players[0].role == Role.DM ? players[0] : players[1];
	}

	private static void updateFlags(){
		authority = session && inGame && myRole() == Role.HERO;
		mirror = session && inGame && myRole() == Role.DM;
	}

	public static String txt( String key, Object... args ){
		return Messages.get(MP.class, key, args);
	}

	public static String myName(){
		String n = SPDSettings.mpName();
		if (n == null || n.trim().isEmpty()){
			return txt("default_name");
		}
		return n.trim();
	}

	// ***** session setup *****

	public static boolean hostRoom(){
		leaveQuietly();
		if (!Net.startServer()){
			return false;
		}
		session = true;
		isHost = true;
		welcomed = true;
		inGame = false;
		gameFinished = false;
		resetPlayers();
		players[0].present = true;
		players[0].name = myName();
		players[0].role = Role.HERO;
		players[0].cls = GamesInProgress.selectedClass;
		players[1].role = Role.DM;
		Discovery.startHosting(myName());
		Discovery.setHostOpen(true);
		updateFlags();
		lobbyVersion++;
		return true;
	}

	public static void joinRoom( String address, int port ){
		leaveQuietly();
		session = true;
		isHost = false;
		welcomed = false;
		inGame = false;
		gameFinished = false;
		resetPlayers();
		joinTimeout = 10f;
		updateFlags();
		Net.connect(address, port);
	}

	private static float joinTimeout = 0;

	private static void resetPlayers(){
		for (Player p : players){
			p.name = "";
			p.role = Role.HERO;
			p.cls = null;
			p.ready = false;
			p.present = false;
		}
		swapRequested = false;
		lobbyVersion++;
	}

	//leaves the session and returns to the title screen, optionally with a message
	public static void leave( String message ){
		leaveQuietly();
		pendingTitleMessage = message;
		ShatteredPixelDungeon.switchNoFade(TitleScene.class);
	}

	public static String pendingTitleMessage = null;
	public static String pendingLobbyMessage = null;

	public static void leaveQuietly(){
		if (session && Net.connected()){
			Net.send(MSG_BYE);
			Net.disconnectAfterFlush();
		}
		Net.stopServer();
		Net.disconnect();
		Net.clearIncoming();
		Discovery.stopHosting();
		Discovery.stopBrowsing();
		session = false;
		isHost = false;
		welcomed = false;
		inGame = false;
		gameFinished = false;
		MPAuthority.reset();
		MPMirror.reset();
		updateFlags();
		resetPlayers();
	}

	// ***** lobby actions (local player) *****

	public static void setMyReady( boolean ready ){
		if (isHost){
			players[0].ready = ready;
			lobbyChanged();
		} else {
			Bundle b = new Bundle();
			b.put("ready", ready);
			Net.send(MSG_READY, b);
			//optimistic, the host confirms
			players[1].ready = ready;
			lobbyVersion++;
		}
	}

	public static void setMyClass( HeroClass cls ){
		me().cls = cls;
		me().ready = false;
		if (isHost){
			lobbyChanged();
		} else {
			Bundle b = new Bundle();
			b.put("cls", cls == null ? "" : cls.name());
			Net.send(MSG_CLASS, b);
			lobbyVersion++;
		}
	}

	public static void setMyName( String name ){
		SPDSettings.mpName(name);
		if (!session) return;
		me().name = myName();
		if (isHost){
			Discovery.setHostName(myName());
			lobbyChanged();
		} else {
			Bundle b = new Bundle();
			b.put("name", myName());
			Net.send(MSG_NAME, b);
			lobbyVersion++;
		}
	}

	public static boolean canStart(){
		if (!players[0].present || !players[1].present) return false;
		if (!players[0].ready || !players[1].ready) return false;
		if (players[0].role == players[1].role) return false;
		return heroPlayer().cls != null;
	}

	public static int readyCount(){
		int n = 0;
		for (Player p : players){
			if (p.present && p.ready) n++;
		}
		return n;
	}

	public static int presentCount(){
		int n = 0;
		for (Player p : players){
			if (p.present) n++;
		}
		return n;
	}

	public static void requestStart(){
		if (!canStart()) return;
		if (isHost){
			startGame();
		} else {
			Net.send(MSG_START_REQ);
		}
	}

	public static void requestSwap(){
		if (!peerPresent()) return;
		if (swapRequested) return;
		swapRequested = true;
		Net.send(MSG_SWAP_REQ);
		notice(txt("swap_sent"));
	}

	private static void lobbyChanged(){
		lobbyVersion++;
		if (isHost){
			sendLobby();
		}
	}

	private static void sendLobby(){
		if (!Net.connected()) return;
		Bundle b = new Bundle();
		for (int i = 0; i < 2; i++){
			Player p = players[i];
			Bundle pb = new Bundle();
			pb.put("name", p.name);
			pb.put("role", p.role.name());
			pb.put("cls", p.cls == null ? "" : p.cls.name());
			pb.put("ready", p.ready);
			pb.put("present", p.present);
			b.put("p" + i, pb);
		}
		Net.send(MSG_LOBBY, b);
	}

	private static void readLobby( Bundle b ){
		for (int i = 0; i < 2; i++){
			Bundle pb = b.getBundle("p" + i);
			if (pb.isNull()) continue;
			Player p = players[i];
			p.name = pb.getString("name");
			p.role = roleOf(pb.getString("role"));
			p.cls = classOf(pb.getString("cls"));
			p.ready = pb.getBoolean("ready");
			p.present = pb.getBoolean("present");
		}
		//a guest that becomes the hero without a class picks their last used one
		if (!isHost && players[1].role == Role.HERO && players[1].cls == null && !inGame){
			setMyClass(defaultClass());
		}
		lobbyVersion++;
	}

	public static HeroClass defaultClass(){
		if (GamesInProgress.selectedClass != null && GamesInProgress.selectedClass.isUnlocked()){
			return GamesInProgress.selectedClass;
		}
		HeroClass cls = HeroClass.values()[SPDSettings.lastClass()];
		return cls.isUnlocked() ? cls : HeroClass.WARRIOR;
	}

	private static Role roleOf( String s ){
		try {
			return Role.valueOf(s);
		} catch (Exception e){
			return Role.DM;
		}
	}

	private static HeroClass classOf( String s ){
		if (s == null || s.isEmpty()) return null;
		try {
			return HeroClass.valueOf(s);
		} catch (Exception e){
			return null;
		}
	}

	//swaps roles in the lobby, host only
	private static void swapLobbyRoles(){
		Role r0 = players[0].role;
		players[0].role = players[1].role;
		players[1].role = r0;
		players[0].ready = players[1].ready = false;
		if (players[0].role == Role.HERO){
			if (players[0].cls == null) players[0].cls = defaultClass();
			players[1].cls = null;
		} else {
			players[0].cls = null;
			//the guest will send its preferred class
		}
		lobbyChanged();
	}

	// ***** game start / end *****

	private static void startGame(){
		if (!canStart()) return;
		Net.send(MSG_START);
		beginGame();
	}

	private static void beginGame(){
		inGame = true;
		gameFinished = false;
		swapRequested = false;
		updateFlags();
		MPAuthority.reset();
		MPMirror.reset();
		if (isHost) Discovery.setHostOpen(false);

		if (myRole() == Role.HERO){
			startHeroGame(me().cls);
		} else {
			MPWaitScene.show(txt("wait_start"));
		}
	}

	private static void startHeroGame( HeroClass cls ){
		deleteSlot();
		GamesInProgress.curSlot = SLOT;
		GamesInProgress.selectedClass = cls;
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		Dungeon.initSeed();
		ActionIndicator.clearAction();
		InterlevelScene.mode = InterlevelScene.Mode.DESCEND;
		Game.switchScene(InterlevelScene.class);
	}

	public static void deleteSlot(){
		String folder = GamesInProgress.gameFolder(SLOT);
		if (FileUtils.dirExists(folder)){
			FileUtils.deleteDir(folder);
		}
		GamesInProgress.setUnknown(SLOT);
	}

	//ends the current game for both players and returns to the lobby
	public static void endGameToLobby( boolean notifyPeer ){
		if (!session) return;
		if (notifyPeer && Net.connected()){
			Net.send(MSG_TO_LOBBY);
		}
		inGame = false;
		gameFinished = false;
		swapRequested = false;
		updateFlags();
		MPAuthority.reset();
		MPMirror.reset();
		for (Player p : players){
			p.ready = false;
		}
		lobbyVersion++;
		ShatteredPixelDungeon.switchNoFade(LobbyScene.class);
	}

	//called on the hero device when the game ends (death or victory)
	public static void onGameFinished( boolean heroWon ){
		if (!inGame || gameFinished) return;
		gameFinished = true;
		//makes sure the other player sees the final state
		MPAuthority.captureSnapshot(false);
		Bundle b = new Bundle();
		b.put("won", heroWon);
		Net.send(MSG_GAME_OVER, b);
	}

	// ***** per-frame processing *****

	public static void update(){
		if (!session) return;

		if (!welcomed && !isHost){
			joinTimeout -= Game.elapsed;
			if (joinTimeout <= 0){
				leave(txt("join_failed"));
				return;
			}
		}

		Net.Frame f;
		int processed = 0;
		while (session && (f = Net.poll()) != null){
			try {
				handle(f);
			} catch (Exception e){
				Game.reportException(e);
			}
			//large batches are split across frames to keep the game responsive
			if (++processed >= 40) break;
		}

		if (mirror){
			MPMirror.flush();
		}
		if (authority){
			MPAuthority.update();
		}
	}

	private static void handle( Net.Frame f ){
		if (MPAutoTest.ENABLED && f.type != MSG_SNAP){
			MPAutoTest.log("recv type=" + f.type + " bytes=" + (f.data == null ? 0 : f.data.length));
		}
		switch (f.type){
			case Net.EV_CONNECTED:
				if (!isHost){
					Bundle b = new Bundle();
					b.put("proto", PROTOCOL);
					b.put("ver", Game.versionCode);
					b.put("name", myName());
					Net.send(MSG_HELLO, b);
				}
				return;
			case Net.EV_CONNECT_FAILED:
				if (!isHost) leave(txt("join_failed"));
				return;
			case Net.EV_DISCONNECTED:
				onPeerLost(false);
				return;
			case MSG_BYE:
				onPeerLost(true);
				return;
		}

		if (isHost && f.type == MSG_HELLO){
			onHello(f.bundle());
			return;
		}
		if (!isHost && f.type == MSG_WELCOME){
			onWelcome(f.bundle());
			return;
		}
		//nothing else is valid before the handshake
		if (!welcomed || !peer().present && isHost) return;

		Bundle b;
		switch (f.type){
			case MSG_LOBBY:
				if (!isHost) readLobby(f.bundle());
				break;
			case MSG_READY:
				if (isHost && !inGame){
					players[1].ready = f.bundle().getBoolean("ready");
					lobbyChanged();
				}
				break;
			case MSG_CLASS:
				if (isHost && !inGame){
					players[1].cls = classOf(f.bundle().getString("cls"));
					players[1].ready = false;
					lobbyChanged();
				}
				break;
			case MSG_NAME:
				if (isHost){
					String n = f.bundle().getString("name");
					players[1].name = n.length() > 20 ? n.substring(0, 20) : n;
					lobbyChanged();
				}
				break;
			case MSG_START_REQ:
				if (isHost && !inGame) startGame();
				break;
			case MSG_START:
				if (!isHost && !inGame) beginGame();
				break;
			case MSG_SWAP_REQ:
				onSwapRequested();
				break;
			case MSG_SWAP_ANS:
				onSwapAnswer(f.bundle().getBoolean("yes"));
				break;
			case MSG_TO_LOBBY:
				if (inGame){
					notice(txt("peer_to_lobby"));
					endGameToLobby(false);
				}
				break;
			case MSG_GAME_OVER:
				if (mirror){
					gameFinished = true;
					MPMirror.onGameOver(f.bundle().getBoolean("won"));
				}
				break;
			case MSG_LEVEL:
				if (mirror) MPMirror.onLevel(f.bundle());
				break;
			case MSG_SNAP:
				if (mirror) MPMirror.onSnap(f.bundle());
				break;
			case MSG_STATE:
				if (mirror) MPMirror.onState(f.bundle());
				break;
			case MSG_NOTICE:
				b = f.bundle();
				String[] args = b.getStringArray("args");
				notice(txt(b.getString("key"), args == null ? new Object[0] : (Object[]) args));
				break;
			case MSG_CMD:
				if (authority) MPAuthority.onCommand(f.bundle());
				break;
			case MSG_RESYNC:
				if (authority) MPAuthority.requestResync();
				break;
			case MSG_HANDOVER:
				if (mirror) receiveHandover(f.data);
				break;
		}
	}

	private static void onHello( Bundle b ){
		Bundle reply = new Bundle();
		int proto = b.getInt("proto");
		int ver = b.getInt("ver");
		if (proto != PROTOCOL || ver != Game.versionCode){
			reply.put("ok", false);
			reply.put("reason", "version");
			Net.send(MSG_WELCOME, reply);
			Net.disconnectAfterFlush();
			return;
		}
		if (inGame){
			reply.put("ok", false);
			reply.put("reason", "ingame");
			Net.send(MSG_WELCOME, reply);
			Net.disconnectAfterFlush();
			return;
		}
		String name = b.getString("name");
		if (name.length() > 20) name = name.substring(0, 20);
		Player guest = players[1];
		guest.present = true;
		guest.name = name;
		guest.ready = false;
		guest.role = players[0].role == Role.HERO ? Role.DM : Role.HERO;
		guest.cls = null;
		players[0].ready = false;
		reply.put("ok", true);
		Net.send(MSG_WELCOME, reply);
		Discovery.setHostOpen(false);
		lobbyChanged();
		notice(txt("peer_joined", name));
	}

	private static void onWelcome( Bundle b ){
		if (b.getBoolean("ok")){
			welcomed = true;
			Discovery.stopBrowsing();
			players[1].present = true;
			players[1].name = myName();
			lobbyVersion++;
			ShatteredPixelDungeon.switchNoFade(LobbyScene.class);
		} else {
			String reason = b.getString("reason");
			String msg;
			if (reason.equals("version"))      msg = txt("reject_version");
			else if (reason.equals("ingame"))  msg = txt("reject_ingame");
			else                               msg = txt("reject_full");
			leave(msg);
		}
	}

	private static void onPeerLost( boolean graceful ){
		if (!session) return;

		if (!isHost && !welcomed){
			leave(txt("join_failed"));
			return;
		}

		String name = peer().name;
		if (inGame){
			if (authority){
				//the hero keeps playing alone, the run becomes a regular save
				String msg = txt("peer_lost_solo", name);
				MPAuthority.convertToSolo();
				leaveQuietlyKeepGame();
				showWindow(new WndMessage(msg));
			} else {
				leave(graceful ? txt("peer_left_game", name) : txt("connection_lost"));
			}
			return;
		}

		if (isHost){
			Net.disconnect();
			players[1].present = false;
			players[1].ready = false;
			players[1].cls = null;
			players[0].ready = false;
			if (swapPrompt != null){
				swapPrompt.hide();
				swapPrompt = null;
			}
			swapRequested = false;
			Discovery.setHostOpen(true);
			lobbyVersion++;
			notice(graceful ? txt("peer_left", name) : txt("peer_lost", name));
		} else {
			leave(graceful ? txt("host_closed") : txt("connection_lost"));
		}
	}

	//used when the hero continues alone, the game itself must be left untouched
	private static void leaveQuietlyKeepGame(){
		Net.stopServer();
		Net.disconnect();
		Net.clearIncoming();
		Discovery.stopHosting();
		Discovery.stopBrowsing();
		session = false;
		isHost = false;
		welcomed = false;
		inGame = false;
		gameFinished = false;
		updateFlags();
		resetPlayers();
	}

	// ***** role swapping *****

	private static void onSwapRequested(){
		if (swapPrompt != null) return;
		if (inGame && gameFinished) return;
		//both players asked at the same time, the host settles it
		if (swapRequested){
			if (isHost){
				swapRequested = false;
				answerSwap(true);
			}
			return;
		}
		if (MPAutoTest.ENABLED && MPAutoTest.autoAcceptSwap()){
			answerSwap(true);
			return;
		}
		String name = peer().name;
		swapPrompt = new WndOptions(Icons.get(Icons.SHUFFLE),
				txt("swap_title"),
				txt("swap_request", name),
				txt("swap_accept"),
				txt("swap_decline")){
			@Override
			protected void onSelect(int index) {
				swapPrompt = null;
				answerSwap(index == 0);
			}

			@Override
			public void onBackPressed() {
				swapPrompt = null;
				super.onBackPressed();
				answerSwap(false);
			}
		};
		showWindow(swapPrompt);
	}

	private static void answerSwap( boolean yes ){
		if (!session || !peerPresent()) return;
		Bundle b = new Bundle();
		b.put("yes", yes);
		Net.send(MSG_SWAP_ANS, b);
		if (yes) performSwap();
	}

	private static void onSwapAnswer( boolean yes ){
		boolean wasRequested = swapRequested;
		swapRequested = false;
		if (!wasRequested) return;
		if (yes){
			notice(txt("swap_accepted"));
			performSwap();
		} else {
			notice(txt("swap_declined"));
		}
	}

	private static void performSwap(){
		if (!inGame){
			if (isHost) swapLobbyRoles();
			return;
		}
		//in game the hero device hands its save over to the other device
		if (authority){
			MPAuthority.beginHandover();
		} else {
			MPWaitScene.show(txt("wait_swap"));
		}
	}

	//called by the hero device once the save was sent
	static void afterHandoverSent(){
		players[0].role = players[0].role == Role.HERO ? Role.DM : Role.HERO;
		players[1].role = players[1].role == Role.HERO ? Role.DM : Role.HERO;
		updateFlags();
		//the authority state is left frozen, the scene change stops the actor thread
		MPMirror.reset();
		lobbyVersion++;
		MPWaitScene.show(txt("wait_swap"));
	}

	private static void receiveHandover( byte[] data ){
		try {
			MPHandover.unpack(data);
		} catch (Exception e){
			Game.reportException(e);
			leave(txt("swap_failed"));
			return;
		}
		players[0].role = players[0].role == Role.HERO ? Role.DM : Role.HERO;
		players[1].role = players[1].role == Role.HERO ? Role.DM : Role.HERO;
		updateFlags();
		MPAuthority.reset();
		MPMirror.reset();
		MPMirror.forgetCopy();
		lobbyVersion++;
		me().cls = null;

		GamesInProgress.setUnknown(SLOT);
		GamesInProgress.curSlot = SLOT;
		ActionIndicator.clearAction();
		InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
		Game.switchScene(InterlevelScene.class);
	}

	// ***** ui helpers *****

	public static void notice( String text ){
		MPNotice.show(text);
	}

	public static void showWindow( Window w ){
		Scene s = Game.scene();
		if (s instanceof GameScene){
			GameScene.show(w);
		} else if (s instanceof PixelScene){
			s.addToFront(w);
		}
	}

	//redirects to the lobby if the hero device reaches the title screen while still in a session
	public static boolean redirectFromTitle(){
		if (!session) return false;
		if (inGame){
			endGameToLobby(true);
		} else {
			ShatteredPixelDungeon.switchNoFade(LobbyScene.class);
		}
		return true;
	}
}
