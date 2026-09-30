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

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.AllyBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Corruption;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.NPC;
import com.shatteredpixel.shatteredpixeldungeon.effects.MagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.HeroSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.MobSprite;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

//The enemy player's view of the game. It never simulates anything,
//it rebuilds the world from the state streamed by the hero device.
public class MPMirror {

	private static int levelId = -1;
	private static Bundle pendingLevel = null;
	private static boolean sceneReady = false;
	private static final ArrayList<Bundle> pendingSnaps = new ArrayList<>();

	public static int possessedId = 0;
	//0 = things are happening, 1 = waiting for the hero, 2 = waiting for us
	public static int turn = 0;
	public static long waitDeadline = 0;
	public static boolean staffMode = false;

	private static boolean followPossessed = true;
	public static Boolean gameOverWon = null;

	public static void reset(){
		levelId = -1;
		pendingLevel = null;
		sceneReady = false;
		pendingSnaps.clear();
		possessedId = 0;
		turn = 0;
		waitDeadline = 0;
		staffMode = false;
		followPossessed = true;
		gameOverWon = null;
	}

	//true while the loaded world is a copy received from the other device
	private static boolean worldIsCopy = false;

	//drops the mirrored world. Only call this once the mirror game scene is gone,
	//as the scene still draws from this state until it is replaced.
	public static void clearWorldIfCopy(){
		if (!worldIsCopy || MP.mirror) return;
		worldIsCopy = false;
		Actor.clear();
		Dungeon.hero = null;
		Dungeon.level = null;
	}

	//the world is about to be replaced by a real save (role swap)
	static void forgetCopy(){
		worldIsCopy = false;
	}

	public static boolean cameraFollowsHero(){
		return !MP.mirror || possessedId == 0;
	}

	public static boolean ready(){
		return sceneReady && GameScene.isMirrorScene();
	}

	// ***** incoming state *****

	static void onLevel( Bundle b ){
		pendingSnaps.clear();
		pendingLevel = b;
		levelId = b.getInt("lid");
		sceneReady = false;
		InterlevelScene.mode = InterlevelScene.Mode.NONE;
		Game.switchScene(GameScene.class, new Game.SceneChangeCallback() {
			@Override
			public void beforeCreate() {
				//the previous scene is gone at this point, safe to swap the world
				if (pendingLevel != null){
					Bundle lvl = pendingLevel;
					pendingLevel = null;
					try {
						restoreWorld(lvl);
					} catch (Exception e){
						Game.reportException(e);
						Net.send(MP.MSG_RESYNC);
					}
				}
			}

			@Override
			public void afterCreate() {
				//do nothing
			}
		});
	}

	private static void restoreWorld( Bundle b ){
		Actor.clear();
		worldIsCopy = true;

		//same as loading a save, but from memory
		Dungeon.hero = null;
		Dungeon.loadGame(b.getBundle("game"), true);
		Hero hero = Dungeon.hero;

		Level level = (Level) b.get("level");
		Dungeon.level = level;
		PathFinder.setMapSize(level.width(), level.height());

		revealAll(level);

		Actor.mirrorAddChar(hero);
		for (Mob m : level.mobs){
			Actor.mirrorAddChar(m);
		}

		applyState(b);
	}

	private static void revealAll( Level level ){
		Arrays.fill(level.heroFOV, true);
		Arrays.fill(level.visited, true);
		Arrays.fill(level.mapped, true);
		for (Heap h : level.heaps.valueList()){
			h.seen = true;
		}
	}

	//called at the end of GameScene.create on the mirror
	public static void onSceneCreated(){
		sceneReady = true;
		staffMode = false;
		focusCamera(true);
	}

	static void onSnap( Bundle b ){
		if (b.getInt("lid") != levelId) return;
		pendingSnaps.add(b);
	}

	static void onState( Bundle b ){
		if (b.getInt("lid") != levelId) return;
		if (!ready()){
			//applied along with the next snapshot
			pendingSnaps.add(b);
			return;
		}
		applyState(b);
	}

	static void onGameOver( boolean heroWon ){
		gameOverWon = heroWon;
		MPHud.showResult(heroWon);
	}

	private static void applyState( Bundle b ){
		int oldPossessed = possessedId;
		if (b.contains("pos")) possessedId = b.getInt("pos");
		if (b.contains("turn")) turn = b.getInt("turn");
		if (b.contains("wl")) waitDeadline = System.currentTimeMillis() + b.getLong("wl");
		if (oldPossessed != possessedId){
			staffMode = false;
			followPossessed = true;
			if (possessedId != 0 && sceneReady){
				focusCamera(false);
			}
		}
	}

	//applies everything received since the last frame
	static void flush(){
		if (pendingSnaps.isEmpty() || !ready()) return;

		Bundle heroSrc = null, mobsSrc = null, heapSrc = null, blobSrc = null, plantSrc = null, trapSrc = null;
		int[] map = null;
		ArrayList<String> events = new ArrayList<>();
		Bundle state = null;

		for (Bundle b : pendingSnaps){
			if (b.contains("hero"))   heroSrc = b;
			if (b.contains("mobs"))   mobsSrc = b;
			if (b.contains("heaps"))  heapSrc = b;
			if (b.contains("blobs"))  blobSrc = b;
			if (b.contains("plants")) plantSrc = b;
			if (b.contains("traps"))  trapSrc = b;
			if (b.contains("map"))    map = b.getIntArray("map");
			if (b.contains("ev"))     events.addAll(Arrays.asList(b.getStringArray("ev")));
			if (b.contains("turn"))   state = b;
		}
		pendingSnaps.clear();

		try {
			applyEvents(events);
			if (heroSrc != null && mobsSrc != null) applyChars(heroSrc, mobsSrc);
			if (map != null)       applyMap(map);
			if (heapSrc != null)   applyHeaps(heapSrc.getBundle("heaps"));
			if (blobSrc != null)   applyBlobs(blobSrc.getBundle("blobs"));
			if (plantSrc != null || trapSrc != null){
				if (plantSrc != null) applyPlants(plantSrc.getBundle("plants"));
				if (trapSrc != null)  applyTraps(trapSrc.getBundle("traps"));
				GameScene.updateMap();
			}
			if (state != null) applyState(state);
		} catch (Exception e){
			Game.reportException(e);
			//something went out of sync, ask for a full copy of the floor
			Net.send(MP.MSG_RESYNC);
		}

		if (possessedId != 0 && followPossessed){
			focusCamera(false);
		}
	}

	private static void applyEvents( ArrayList<String> events ){
		for (String e : events){
			String[] p = e.split("\\|", 5);
			try {
				int id = Integer.parseInt(p[1]);
				Actor a = Actor.findById(id);
				if (!(a instanceof Char)) continue;
				Char ch = (Char) a;
				CharSprite s = ch.sprite;
				if (s == null || s.parent == null) continue;
				switch (p[0]){
					case "a":
						s.mpPlayAttack(Integer.parseInt(p[2]));
						break;
					case "z":
						int cell = Integer.parseInt(p[2]);
						s.turnTo(ch.pos, cell);
						if (cell >= 0 && cell < Dungeon.level.length()){
							((MagicMissile) s.parent.recycle(MagicMissile.class)).reset(
									MagicMissile.MAGIC_MISSILE, s, cell, null);
						}
						break;
					case "s":
						int color = Integer.parseInt(p[2]);
						int icon = Integer.parseInt(p[3]);
						s.showStatusWithIcon(color, p[4], icon);
						if (color == CharSprite.NEGATIVE || color == CharSprite.WARNING){
							if (p[4].matches("-?\\d+")){
								Sample.INSTANCE.play(Assets.Sounds.HIT, 0.6f);
							}
						}
						break;
				}
			} catch (Exception ex){
				//malformed or outdated event, ignore
			}
		}
	}

	private static HashSet<Class<?>> buffClasses( Char ch ){
		HashSet<Class<?>> result = new HashSet<>();
		for (Buff b : ch.buffs()){
			result.add(b.getClass());
		}
		return result;
	}

	private static void relink( Char oldCh, Char newCh ){
		CharSprite s = oldCh.sprite;
		HashSet<Class<?>> oldCls = buffClasses(oldCh);
		HashSet<Class<?>> newCls = buffClasses(newCh);
		for (Buff b : oldCh.buffs()){
			if (!newCls.contains(b.getClass())){
				try { b.fx(false); } catch (Exception e) { /* visual only */ }
			}
		}
		s.mpRelink(newCh);
		for (Buff b : newCh.buffs()){
			if (!oldCls.contains(b.getClass())){
				try { b.fx(true); } catch (Exception e) { /* visual only */ }
			}
		}

		if (oldCh.pos != newCh.pos){
			if (Dungeon.level.adjacent(oldCh.pos, newCh.pos) && s.parent != null){
				if (s.isMoving) s.interruptMotion();
				s.move(oldCh.pos, newCh.pos);
			} else {
				s.interruptMotion();
				s.isMoving = false;
				s.place(newCh.pos);
			}
		}

		if (oldCh.isAlive() && !newCh.isAlive()){
			s.die();
		}
	}

	private static void applyChars( Bundle heroSrc, Bundle mobsSrc ){
		Level level = Dungeon.level;
		Hero oldHero = Dungeon.hero;

		HashMap<Integer, Mob> oldMobs = new HashMap<>();
		for (Mob m : level.mobs){
			oldMobs.put(m.id(), m);
		}

		//ids must be free so restored characters keep them
		Actor.mirrorClearChars();

		Hero newHero = null;
		Dungeon.hero = null;
		try {
			newHero = (Hero) heroSrc.get("hero");
		} catch (Exception e){
			Game.reportException(e);
		}
		if (newHero == null) newHero = oldHero;
		Dungeon.hero = newHero;
		Actor.mirrorAddChar(newHero);

		if (newHero != oldHero && oldHero != null && oldHero.sprite != null){
			int oldTier = oldHero.tier();
			relink(oldHero, newHero);
			if (newHero.tier() != oldTier && newHero.sprite instanceof HeroSprite){
				((HeroSprite) newHero.sprite).updateArmor();
			}
		}

		ArrayList<Mob> newMobs = new ArrayList<>();
		for (Bundlable x : mobsSrc.getCollection("mobs")){
			if (x instanceof Mob) newMobs.add((Mob) x);
		}

		HashSet<Mob> fresh = new HashSet<>();
		for (Mob m : newMobs){
			Mob old = oldMobs.remove(m.id());
			Actor.mirrorAddChar(m);
			fresh.add(m);
			if (old != null && old.getClass() == m.getClass() && old.sprite != null && old.sprite.parent != null){
				relink(old, m);
			} else {
				if (old != null && old.sprite != null){
					old.sprite.killAndErase();
				}
				try {
					GameScene.addSprite(m);
				} catch (Exception e){
					Game.reportException(e);
				}
			}
		}

		for (Mob gone : oldMobs.values()){
			if (gone.sprite == null) continue;
			if (gone.sprite instanceof MobSprite && gone.sprite.parent != null && gone.isAlive()){
				gone.sprite.die();
			} else if (gone.isAlive()) {
				gone.sprite.killAndErase();
			}
		}

		level.mobs = fresh;
	}

	private static void applyMap( int[] map ){
		Level level = Dungeon.level;
		if (map.length != level.length()) return;
		int changed = 0;
		for (int i = 0; i < map.length; i++){
			if (map[i] != level.map[i]){
				Level.set(i, map[i], level);
				changed++;
				if (changed <= 40) GameScene.updateMap(i);
			}
		}
		if (changed > 40){
			GameScene.updateMap();
		}
	}

	private static void applyHeaps( Bundle hb ){
		Level level = Dungeon.level;
		for (Heap h : level.heaps.valueList()){
			if (h.sprite != null){
				h.sprite.killAndErase();
				h.sprite = null;
			}
		}
		level.heaps.clear();
		for (Bundlable x : hb.getCollection("x")){
			if (!(x instanceof Heap)) continue;
			Heap h = (Heap) x;
			if (h.isEmpty()) continue;
			h.seen = true;
			level.heaps.put(h.pos, h);
			GameScene.add(h);
		}
	}

	private static void applyBlobs( Bundle bb ){
		Level level = Dungeon.level;
		HashMap<Class<? extends Blob>, Blob> fresh = new HashMap<>();
		for (Bundlable x : bb.getCollection("x")){
			if (x instanceof Blob) fresh.put(((Blob) x).getClass(), (Blob) x);
		}
		for (Map.Entry<Class<? extends Blob>, Blob> e : level.blobs.entrySet()){
			if (!fresh.containsKey(e.getKey())){
				Blob old = e.getValue();
				old.volume = 0;
				if (old.cur != null) Arrays.fill(old.cur, 0);
				old.area.setEmpty();
			}
		}
		for (Blob n : fresh.values()){
			if (n.cur == null) n.cur = new int[level.length()];
			Blob old = level.blobs.get(n.getClass());
			if (old != null){
				old.cur = n.cur;
				old.volume = n.volume;
				old.area.setEmpty();
			} else {
				level.blobs.put(n.getClass(), n);
				n.emitter = null;
				GameScene.addBlobSprite(n, true);
			}
		}
	}

	private static void applyPlants( Bundle pb ){
		Level level = Dungeon.level;
		level.plants.clear();
		for (Bundlable x : pb.getCollection("x")){
			if (x instanceof Plant) level.plants.put(((Plant) x).pos, (Plant) x);
		}
	}

	private static void applyTraps( Bundle tb ){
		Level level = Dungeon.level;
		level.traps.clear();
		for (Bundlable x : tb.getCollection("x")){
			if (x instanceof Trap) level.traps.put(((Trap) x).pos, (Trap) x);
		}
	}

	// ***** input from the enemy player *****

	public static Mob possessed(){
		if (possessedId == 0) return null;
		Actor a = Actor.findById(possessedId);
		return a instanceof Mob ? (Mob) a : null;
	}

	//rough local check, the hero device has the final word
	public static boolean looksControllable( Mob m ){
		return m != null && m.isAlive() && m.alignment == Char.Alignment.ENEMY
				&& !(m instanceof NPC) && !Char.hasProp(m, Char.Property.BOSS)
				&& m.buff(Corruption.class) == null && m.buff(AllyBuff.class) == null;
	}

	private static Mob mobAt( int cell ){
		for (Mob m : Dungeon.level.mobs){
			if (m.pos == cell) return m;
		}
		return null;
	}

	public static void onCellSelected( Integer cell ){
		if (cell == null || Dungeon.level == null || cell < 0 || cell >= Dungeon.level.length()) return;
		if (MP.gameFinished()) return;

		Mob m = mobAt(cell);

		if (staffMode){
			if (m == null){
				MP.notice(MP.txt("staff_pick"));
				return;
			}
			if (m.id() == possessedId){
				staffMode = false;
				return;
			}
			if (!looksControllable(m)){
				MP.notice(MP.txt("cant_control"));
				return;
			}
			staffMode = false;
			command("swap", m.id(), -1);
			return;
		}

		if (possessedId == 0){
			if (m != null){
				if (looksControllable(m)){
					command("possess", m.id(), -1);
				} else {
					MP.notice(MP.txt("cant_control"));
				}
			} else {
				MP.notice(MP.txt("pick_enemy"));
			}
			return;
		}

		Mob mine = possessed();
		if (mine != null && cell == mine.pos){
			command("wait", 0, -1);
		} else if (m != null && m != mine && looksControllable(m)){
			//switching creatures is only possible with the swap staff
			MP.notice(MP.txt("use_staff"));
		} else {
			followPossessed = true;
			command("cell", 0, cell);
		}
	}

	public static void keyMove( int dx, int dy ){
		Mob mine = possessed();
		if (mine == null || MP.gameFinished()) return;
		int x = mine.pos % Dungeon.level.width() + dx;
		int y = mine.pos / Dungeon.level.width() + dy;
		if (x < 0 || y < 0 || x >= Dungeon.level.width() || y >= Dungeon.level.height()) return;
		followPossessed = true;
		command("cell", 0, x + y * Dungeon.level.width());
	}

	public static void waitTurn(){
		if (possessedId == 0) return;
		command("wait", 0, -1);
	}

	public static void release(){
		if (possessedId == 0) return;
		staffMode = false;
		command("release", 0, -1);
	}

	public static void toggleStaff(){
		if (possessedId == 0){
			MP.notice(MP.txt("staff_need"));
			return;
		}
		staffMode = !staffMode;
		if (staffMode) MP.notice(MP.txt("staff_pick"));
	}

	private static void command( String kind, int id, int cell ){
		Bundle b = new Bundle();
		b.put("k", kind);
		b.put("id", id);
		b.put("cell", cell);
		Net.send(MP.MSG_CMD, b);
	}

	//called when the player drags the camera, stops auto-following
	public static void onCameraDragged(){
		followPossessed = false;
	}

	public static void focusCamera( boolean snap ){
		CharSprite target = null;
		Mob mine = possessed();
		if (mine != null && mine.sprite != null){
			target = mine.sprite;
		} else if (Dungeon.hero != null && Dungeon.hero.sprite != null){
			target = Dungeon.hero.sprite;
		}
		if (target == null) return;
		if (snap){
			Camera.main.snapTo(target.center());
		}
		Camera.main.panFollow(target, 20f);
	}

	public static void centerOn( boolean hero ){
		CharSprite target = null;
		if (!hero && possessed() != null) target = possessed().sprite;
		if (target == null && Dungeon.hero != null) target = Dungeon.hero.sprite;
		if (target == null) return;
		followPossessed = !hero;
		Camera.main.panFollow(target, 20f);
		if (hero) Camera.main.panTo(target.center(), 20f);
	}
}
