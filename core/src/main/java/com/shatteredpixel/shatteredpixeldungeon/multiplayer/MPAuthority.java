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
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.AllyBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Corruption;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.NPC;
import com.shatteredpixel.shatteredpixeldungeon.effects.Speck;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.noosa.particles.Emitter;
import com.watabou.utils.Bundle;
import com.watabou.utils.FileUtils;

import java.util.ArrayList;
import java.util.Arrays;

//Game logic on the device that runs the real game (the hero player).
//Handles the enemy player's possessed creature and streams the game state to the other device.
public class MPAuthority {

	//how long the enemy player has to act before their creature acts on its own
	public static final long TURN_TIME = 25000;
	//how often (at most) the state is streamed while things are happening
	private static final long SNAP_INTERVAL = 70;

	private static final Object LOCK = new Object();

	public static final int CMD_CELL = 0;
	public static final int CMD_WAIT = 1;

	public static class Cmd {
		final int kind;
		final int cell;
		//false for automatic travel steps
		final boolean explicit;

		Cmd( int kind, int cell, boolean explicit ){
			this.kind = kind;
			this.cell = cell;
			this.explicit = explicit;
		}

		public boolean isWait(){
			return kind == CMD_WAIT;
		}

		public int cell(){
			return cell;
		}

		public boolean explicit(){
			return explicit;
		}
	}

	public static final Cmd AI_TURN = new Cmd(-1, -1, false);
	public static final Cmd INTERRUPTED = new Cmd(-2, -1, false);

	public static volatile int possessedId = 0;
	private static volatile Cmd pending = null;
	public static volatile boolean waitingForDM = false;
	public static volatile long waitStart = 0;
	private static volatile boolean frozen = false;
	private static int timeouts = 0;

	//automatic travel of the possessed creature, only touched by the actor thread
	private static int travelCell = -1;
	private static int travelCharId = 0;
	private static int travelHP = -1;

	private static volatile boolean handoverPending = false;

	// ***** snapshot state *****
	private static final Object SNAP_LOCK = new Object();
	private static Level lastLevel = null;
	private static int levelId = 0;
	private static int[] lastMap = null;
	private static String lastHeaps = null;
	private static String lastBlobs = null;
	private static String lastPlants = null;
	private static String lastTraps = null;
	private static String lastCustom = null;
	private static long lastSnap = 0;
	private static volatile boolean resync = false;
	private static final ArrayList<String> events = new ArrayList<>();

	public static void reset(){
		synchronized (LOCK){
			possessedId = 0;
			pending = null;
			waitingForDM = false;
			frozen = false;
			timeouts = 0;
			LOCK.notifyAll();
		}
		travelCell = -1;
		travelCharId = 0;
		handoverPending = false;
		synchronized (SNAP_LOCK){
			lastLevel = null;
			lastMap = null;
			lastHeaps = lastBlobs = lastPlants = lastTraps = lastCustom = null;
			resync = false;
		}
		synchronized (events){
			events.clear();
		}
	}

	// ***** possession *****

	public static boolean isPossessed( Mob m ){
		return MP.authority && possessedId != 0 && m.id() == possessedId;
	}

	public static boolean canControl( Mob m ){
		if (m == null || !m.isAlive()) return false;
		if (m.alignment != Char.Alignment.ENEMY) return false;
		if (m instanceof NPC) return false;
		if (Char.hasProp(m, Char.Property.BOSS)) return false;
		if (m.buff(Corruption.class) != null || m.buff(AllyBuff.class) != null) return false;
		return Dungeon.level != null && Dungeon.level.mobs.contains(m);
	}

	public static Mob possessed(){
		if (possessedId == 0) return null;
		Actor a = Actor.findById(possessedId);
		return a instanceof Mob ? (Mob) a : null;
	}

	private static Mob findMob( int id ){
		if (Dungeon.level == null) return null;
		for (Mob m : Dungeon.level.mobs.toArray(new Mob[0])){
			if (m.id() == id) return m;
		}
		return null;
	}

	//render thread, commands from the enemy player
	static void onCommand( Bundle b ){
		String k = b.getString("k");
		switch (k){
			case "possess":
				tryPossess(b.getInt("id"), false);
				break;
			case "swap":
				tryPossess(b.getInt("id"), true);
				break;
			case "release":
				if (possessedId != 0){
					release();
					notifyDM("released");
				}
				break;
			case "cell":
				deliver(new Cmd(CMD_CELL, b.getInt("cell"), true));
				break;
			case "wait":
				deliver(new Cmd(CMD_WAIT, -1, true));
				break;
		}
	}

	private static void tryPossess( int id, boolean viaStaff ){
		if (handoverPending || MP.gameFinished()) return;
		Mob m = findMob(id);
		if (m == null || !canControl(m)){
			notifyDM("cant_control");
			sendState();
			return;
		}
		if (m.id() == possessedId) return;
		if (possessedId != 0 && !viaStaff){
			notifyDM("use_staff");
			return;
		}
		Mob old = possessed();
		synchronized (LOCK){
			possessedId = m.id();
			pending = null;
			timeouts = 0;
			travelCell = -1;
			travelCharId = 0;
			//wakes the previous creature if it was waiting, it will act on its own
			LOCK.notifyAll();
		}
		if (m.sprite != null && m.sprite.parent != null){
			Emitter e = m.sprite.emitter();
			if (e != null) e.burst(Speck.factory(Speck.LIGHT), 6);
			m.sprite.showAlert();
		}
		if (old != null && viaStaff && old.sprite != null && old.sprite.parent != null){
			Emitter e = old.sprite.emitter();
			if (e != null) e.burst(Speck.factory(Speck.STAR), 4);
		}
		GLog.w(MP.txt("hero_possessed", MP.peer().name, Messages.titleCase(m.name())));
		sendState();
	}

	public static void release(){
		synchronized (LOCK){
			possessedId = 0;
			pending = null;
			LOCK.notifyAll();
		}
		sendState();
	}

	//called when a creature dies or leaves the level
	public static void onMobGone( Mob m ){
		if (!MP.authority || possessedId == 0 || m.id() != possessedId) return;
		synchronized (LOCK){
			possessedId = 0;
			pending = null;
			LOCK.notifyAll();
		}
		notifyDM("creature_died");
		sendState();
	}

	//actor thread, the creature can no longer be controlled (e.g. it became an ally)
	public static void lostControl( Mob m ){
		if (possessedId == 0 || m.id() != possessedId) return;
		synchronized (LOCK){
			possessedId = 0;
			pending = null;
			LOCK.notifyAll();
		}
		notifyDM("lost_control");
		sendState();
	}

	//actor thread, an explicit move order could not be followed
	public static void notifyBlocked(){
		notifyDM("cant_move");
	}

	private static void deliver( Cmd c ){
		if (possessedId == 0) return;
		synchronized (LOCK){
			pending = c;
			LOCK.notifyAll();
		}
	}

	// ***** actor thread side, used by Mob.mpAct *****

	//the time limit covers the whole turn, failed orders do not restart it
	private static long turnWaitStart = 0;

	public static void beginTurn(){
		turnWaitStart = 0;
	}

	public static void stopTravel(){
		travelCell = -1;
		travelCharId = 0;
	}

	public static void travelTo( int cell, int charId, int hp ){
		travelCell = cell;
		travelCharId = charId;
		travelHP = hp;
	}

	public static void travelStepped( int hp ){
		travelHP = hp;
	}

	//returns the next thing the possessed creature should do, blocking the actor thread if needed
	public static Cmd nextCommand( Mob m ){
		Cmd travel = travelStep(m);
		if (travel != null){
			synchronized (LOCK){
				//a fresh order always overrides automatic travel
				if (pending != null){
					Cmd c = pending;
					pending = null;
					return c;
				}
			}
			return travel;
		}

		synchronized (LOCK){
			if (pending != null){
				Cmd c = pending;
				pending = null;
				timeouts = 0;
				return c;
			}
		}

		if (turnWaitStart == 0){
			turnWaitStart = System.currentTimeMillis();
		}
		waitStart = turnWaitStart;
		waitingForDM = true;
		captureSnapshot(true);

		try {
			synchronized (LOCK){
				while (true){
					//while handing the game over, the creature must not act at all
					if (frozen){
						LOCK.wait(100);
						continue;
					}
					if (!MP.authority || possessedId != m.id()){
						return AI_TURN;
					}
					if (pending != null){
						Cmd c = pending;
						pending = null;
						timeouts = 0;
						return c;
					}
					if (!frozen && System.currentTimeMillis() - waitStart > TURN_TIME){
						timeouts++;
						if (timeouts >= 2){
							possessedId = 0;
							notifyDM("afk");
						} else {
							notifyDM("timeout");
						}
						return AI_TURN;
					}
					LOCK.wait(100);
				}
			}
		} catch (InterruptedException e){
			Thread.currentThread().interrupt();
			return INTERRUPTED;
		} finally {
			waitingForDM = false;
		}
	}

	private static Cmd travelStep( Mob m ){
		if (travelCell == -1 && travelCharId == 0) return null;

		int dest = travelCell;
		if (travelCharId != 0){
			Actor a = Actor.findById(travelCharId);
			if (!(a instanceof Char) || !((Char) a).isAlive()){
				stopTravel();
				return null;
			}
			Char c = (Char) a;
			if (m.fieldOfView != null && c.pos < m.fieldOfView.length && m.fieldOfView[c.pos] && c.invisible <= 0){
				dest = c.pos;
				travelCell = dest;
			}
		}

		//stop if hurt, or if something to attack showed up (unless we are chasing it)
		if (dest == -1 || dest == m.pos || m.HP < travelHP){
			stopTravel();
			return null;
		}
		if (travelCharId == 0 && m.mpHasTargetInReach()){
			stopTravel();
			return null;
		}
		return new Cmd(CMD_CELL, dest, false);
	}

	// ***** snapshots *****

	//actor thread, after each actor acts
	public static void afterAct( Actor acting ){
		if (!MP.authority || !(acting instanceof Char)) return;
		boolean important = (acting == Dungeon.hero && !Dungeon.hero.resting)
				|| (possessedId != 0 && acting.id() == possessedId);
		if (important || System.currentTimeMillis() - lastSnap >= SNAP_INTERVAL){
			captureSnapshot(false);
		}
	}

	//actor thread, right before it waits for input
	public static void beforeIdle(){
		if (!MP.authority) return;
		boolean important = Dungeon.hero != null && Dungeon.hero.ready;
		if (important || System.currentTimeMillis() - lastSnap >= SNAP_INTERVAL){
			captureSnapshot(false);
		}
	}

	public static void requestResync(){
		resync = true;
		//if nothing is acting right now, send it immediately
		if (Dungeon.hero != null && (Dungeon.hero.ready || waitingForDM)){
			captureSnapshot(false);
		}
	}

	private static String collectionString( java.util.Collection<? extends com.watabou.utils.Bundlable> items ){
		Bundle b = new Bundle();
		b.put("x", items);
		return b.toString();
	}

	private static String customString( Level level ){
		Bundle b = new Bundle();
		b.put("a", level.customTiles);
		b.put("b", level.customWalls);
		b.put("c", level.customTerrain);
		return b.toString();
	}

	public static void captureSnapshot( boolean waiting ){
		if (!MP.authority || !Net.connected()) return;
		synchronized (SNAP_LOCK){
			try {
				Level level = Dungeon.level;
				if (level == null || Dungeon.hero == null || handoverPending && !waitingForDM && !Dungeon.hero.ready){
					return;
				}

				String custom = customString(level);
				if (level != lastLevel || resync || !custom.equals(lastCustom)){
					sendLevel(level, custom);
					return;
				}

				Bundle b = new Bundle();
				b.put("lid", levelId);
				b.put("hero", Dungeon.hero);
				ArrayList<Mob> mobs = new ArrayList<>(Arrays.asList(level.mobs.toArray(new Mob[0])));
				b.put("mobs", mobs);

				String heaps = collectionString(level.heaps.valueList());
				String blobs = collectionString(level.blobs.values());
				String plants = collectionString(level.plants.valueList());
				String traps = collectionString(level.traps.valueList());
				boolean mapChanged = lastMap == null || !Arrays.equals(lastMap, level.map);

				if (!heaps.equals(lastHeaps))   b.put("heaps", Bundle.fromString(heaps));
				if (!blobs.equals(lastBlobs))   b.put("blobs", Bundle.fromString(blobs));
				if (!plants.equals(lastPlants)) b.put("plants", Bundle.fromString(plants));
				if (!traps.equals(lastTraps))   b.put("traps", Bundle.fromString(traps));
				if (mapChanged)                 b.put("map", level.map);

				putState(b);
				putEvents(b);

				Net.send(MP.MSG_SNAP, b, true);

				lastHeaps = heaps;
				lastBlobs = blobs;
				lastPlants = plants;
				lastTraps = traps;
				if (mapChanged) lastMap = level.map.clone();
				lastSnap = System.currentTimeMillis();
			} catch (Exception e){
				//usually a concurrent modification from an animation, the next snapshot will catch up
			}
		}
	}

	private static void sendLevel( Level level, String custom ) throws Exception {
		Bundle b = new Bundle();

		if (level != lastLevel){
			levelId++;
			//creatures do not follow the hero between floors
			if (possessedId != 0){
				synchronized (LOCK){
					possessedId = 0;
					pending = null;
					LOCK.notifyAll();
				}
			}
			stopTravel();
		}

		b.put("lid", levelId);
		//the whole run (hero, item names, notes, statistics...) plus the current floor
		b.put("game", Dungeon.gameBundle());
		b.put("level", level);
		putState(b);
		synchronized (events){
			events.clear();
		}

		String heaps = collectionString(level.heaps.valueList());
		String blobs = collectionString(level.blobs.values());
		String plants = collectionString(level.plants.valueList());
		String traps = collectionString(level.traps.valueList());
		int[] map = level.map.clone();

		Net.send(MP.MSG_LEVEL, b, true);

		lastLevel = level;
		lastCustom = custom;
		lastHeaps = heaps;
		lastBlobs = blobs;
		lastPlants = plants;
		lastTraps = traps;
		lastMap = map;
		resync = false;
		lastSnap = System.currentTimeMillis();
	}

	private static int turnState(){
		if (waitingForDM) return 2;
		if (Dungeon.hero != null && Dungeon.hero.ready) return 1;
		return 0;
	}

	private static void putState( Bundle b ){
		b.put("pos", possessedId);
		b.put("turn", turnState());
		long left = waitingForDM ? Math.max(0, TURN_TIME - (System.currentTimeMillis() - waitStart)) : 0;
		b.put("wl", left);
	}

	private static void putEvents( Bundle b ){
		synchronized (events){
			if (!events.isEmpty()){
				b.put("ev", events.toArray(new String[0]));
				events.clear();
			}
		}
	}

	//small update used when only the possession/turn changed
	public static void sendState(){
		if (!MP.authority) return;
		Bundle b = new Bundle();
		b.put("lid", levelId);
		putState(b);
		Net.send(MP.MSG_STATE, b);
	}

	private static void notifyDM( String key, String... args ){
		Bundle b = new Bundle();
		b.put("key", key);
		b.put("args", args);
		Net.send(MP.MSG_NOTICE, b);
	}

	// ***** visual events, recorded from sprites on any thread *****

	private static void event( String e ){
		synchronized (events){
			//no one is listening if nothing is sent for a while, keep this bounded
			if (events.size() < 200) events.add(e);
		}
	}

	public static void recordAttack( Char ch, int cell ){
		if (!MP.authority || ch == null) return;
		event("a|" + ch.id() + "|" + cell);
	}

	public static void recordZap( Char ch, int cell ){
		if (!MP.authority || ch == null) return;
		event("z|" + ch.id() + "|" + cell);
	}

	public static void recordStatus( Char ch, int color, String text, int icon ){
		if (!MP.authority || ch == null || text == null) return;
		event("s|" + ch.id() + "|" + color + "|" + icon + "|" + text);
	}

	//render thread, the hero started moving to another floor
	public static void onTransition(){
		Bundle b = new Bundle();
		b.put("lid", levelId);
		b.put("pos", 0);
		b.put("turn", 3);
		b.put("wl", 0L);
		Net.send(MP.MSG_STATE, b);
	}

	// ***** per frame (render thread) *****

	static void update(){
		if (handoverPending && Game.scene() instanceof GameScene && Dungeon.hero != null){
			if (!Dungeon.hero.isAlive() || MP.gameFinished()){
				handoverPending = false;
				frozen = false;
				MP.notice(MP.txt("swap_failed"));
			} else if (Dungeon.hero.ready || waitingForDM){
				doHandover();
			} else if (Dungeon.hero.curAction != null){
				Dungeon.hero.interrupt();
			}
		}
	}

	// ***** role swap *****

	static void beginHandover(){
		handoverPending = true;
		frozen = true;
		if (Dungeon.hero != null){
			Dungeon.hero.interrupt();
		}
	}

	private static void doHandover(){
		handoverPending = false;
		byte[] data;
		try {
			GameScene.cancel();
			Dungeon.saveAll();
			data = MPHandover.pack(MP.SLOT);
		} catch (Exception e){
			Game.reportException(e);
			frozen = false;
			MP.notice(MP.txt("swap_failed"));
			return;
		}
		Net.send(MP.MSG_HANDOVER, data, false);
		MP.afterHandoverSent();
	}

	// ***** connection lost: keep playing alone *****

	static void convertToSolo(){
		synchronized (LOCK){
			possessedId = 0;
			pending = null;
			frozen = false;
			LOCK.notifyAll();
		}
		handoverPending = false;

		int slot = GamesInProgress.firstEmpty();
		if (slot == -1) return;
		try {
			String from = GamesInProgress.gameFolder(MP.SLOT);
			String to = GamesInProgress.gameFolder(slot);
			for (String file : FileUtils.filesInDir(from)){
				byte[] bytes = FileUtils.getFileHandle(from + "/" + file).readBytes();
				FileUtils.getFileHandle(to + "/" + file).writeBytes(bytes, false);
			}
			GamesInProgress.curSlot = slot;
			GamesInProgress.setUnknown(slot);
			MP.deleteSlot();
		} catch (Exception e){
			Game.reportException(e);
		}
	}
}
