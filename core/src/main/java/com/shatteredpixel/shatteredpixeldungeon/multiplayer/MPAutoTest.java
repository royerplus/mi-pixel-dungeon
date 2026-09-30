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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Scene;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;

import java.util.ArrayList;

//Developer tool: when the SPD_AUTOTEST environment variable is set (desktop only), the game plays
//an online session by itself so the networking can be tested with two copies on one computer.
//Never active in normal play.
public class MPAutoTest {

	public static final String MODE = safeEnv("SPD_AUTOTEST");
	public static final boolean ENABLED = MODE != null && !MODE.isEmpty();
	private static final String SHOTS = safeEnv("SPD_SHOTS");
	private static final String SWAP_AT = safeEnv("SPD_SWAP_AT");
	private static final String DIE_AT = safeEnv("SPD_DIE_AT");
	private static boolean died = false;
	//walks through the online menus slowly so screenshots of them can be checked
	private static final boolean TOUR = safeEnv("SPD_TOUR") != null && !safeEnv("SPD_TOUR").isEmpty();
	private static int tourStep = 0;
	private static float tourTimer = 0;

	private static String safeEnv( String key ){
		try {
			return System.getenv(key);
		} catch (Exception e){
			return null;
		}
	}

	public static void log( String msg ){
		if (ENABLED){
			System.out.println("[MP " + (System.currentTimeMillis() % 1000000) + "] " + msg);
		}
	}

	private static float timer = 0;
	private static float shotTimer = 3;
	private static int shotCount = 0;
	private static float gameTime = 0;
	private static boolean swapRequested = false;
	private static float lobbyTime = 0;
	private static int actionsOnLevel = 0;
	private static Object lastLevel = null;
	private static String lastScene = "";
	private static float staffTimer = 0;
	private static int dmTries = 0;

	public static void update(){
		if (!ENABLED) return;

		Scene s = Game.scene();
		if (s == null) return;
		String sceneName = s.getClass().getSimpleName();
		if (!sceneName.equals(lastScene)){
			log("scene " + lastScene + " -> " + sceneName + " role=" + (MP.sessionActive() ? MP.myRole() : "-")
					+ " authority=" + MP.authority + " mirror=" + MP.mirror);
			lastScene = sceneName;
		}

		screenshots();

		timer -= Game.elapsed;
		if (timer > 0) return;
		timer = 0.25f;

		try {
			if (s instanceof com.shatteredpixel.shatteredpixeldungeon.scenes.WelcomeScene){
				SPDSettings.version(Game.versionCode);
				SPDSettings.intro(false);
				Game.switchScene(TitleScene.class);
			} else if (s instanceof TitleScene){
				if (TOUR && tourStep < 2){
					tourTitle(s);
				} else {
					title();
				}
			} else if (s instanceof com.shatteredpixel.shatteredpixeldungeon.scenes.HeroSelectScene){
				tourTimer -= 0.25f;
				if (tourTimer <= 0){
					MP.setMyClass(HeroClass.WARRIOR);
					com.shatteredpixel.shatteredpixeldungeon.scenes.HeroSelectScene.onlineMode = false;
					Game.switchScene(LobbyScene.class);
				}
			} else if (s instanceof LobbyScene){
				lobby();
			} else if (s instanceof GameScene){
				closeWindows(s);
				if (MP.authority){
					gameTime += 0.25f;
					maybeDie();
					hero();
					maybeSwap();
				} else if (MP.mirror){
					gameTime += 0.25f;
					dm();
					maybeSwap();
				}
			}
		} catch (Exception e){
			log("autotest error " + e);
			Game.reportException(e);
		}
	}

	private static void screenshots(){
		if (SHOTS == null) return;
		shotTimer -= Game.elapsed;
		if (shotTimer > 0) return;
		shotTimer = TOUR ? 1.5f : 4f;
		try {
			int w = Gdx.graphics.getBackBufferWidth();
			int h = Gdx.graphics.getBackBufferHeight();
			Pixmap p = Pixmap.createFromFrameBuffer(0, 0, w, h);
			PixmapIO.writePNG(Gdx.files.absolute(SHOTS + "/shot_" + MODE + "_" + (shotCount++ % 6) + ".png"), p, 1, true);
			p.dispose();
		} catch (Exception e){
			log("screenshot failed " + e);
		}
	}

	private static void title(){
		SPDSettings.intro(false);
		if (MODE.equals("host")){
			SPDSettings.mpName("Anfitrion");
			if (MP.hostRoom()){
				MP.setMyClass(HeroClass.WARRIOR);
				log("hosting");
				Game.switchScene(LobbyScene.class);
			}
		} else {
			SPDSettings.mpName("Invitado");
			Discovery.startBrowsing();
			ArrayList<Discovery.Room> rooms = Discovery.rooms();
			if (!rooms.isEmpty() && !MP.sessionActive()){
				Discovery.Room r = rooms.get(0);
				log("joining " + r.address + ":" + r.port + " " + r.name);
				MP.joinRoom(r.address, r.port);
			}
		}
	}

	private static void tourTitle( Scene s ){
		SPDSettings.intro(false);
		tourTimer -= 0.25f;
		if (tourTimer > 0) return;
		if (MODE.equals("host")){
			if (tourStep == 0){
				SPDSettings.mpName("Anfitrion");
				s.addToFront(new WndOnline());
				tourTimer = 4;
				tourStep = 1;
			} else {
				tourStep = 2;
				closeAll(s);
				if (MP.hostRoom()){
					com.shatteredpixel.shatteredpixeldungeon.scenes.HeroSelectScene.onlineMode = true;
					com.shatteredpixel.shatteredpixeldungeon.GamesInProgress.selectedClass = HeroClass.WARRIOR;
					Game.switchScene(com.shatteredpixel.shatteredpixeldungeon.scenes.HeroSelectScene.class);
					tourTimer = 4;
				}
			}
		} else {
			if (tourStep == 0){
				SPDSettings.mpName("Invitado");
				s.addToFront(new WndJoin());
				tourTimer = 8;
				tourStep = 1;
			} else {
				tourStep = 2;
				ArrayList<Discovery.Room> rooms = Discovery.rooms();
				closeAll(s);
				if (!rooms.isEmpty()){
					MP.joinRoom(rooms.get(0).address, rooms.get(0).port);
				}
			}
		}
	}

	private static void closeAll( Scene s ){
		for (Gizmo g : s.membersCopy()){
			if (g instanceof Window) ((Window) g).hide();
		}
	}

	private static void lobby(){
		lobbyTime += 0.25f;
		if (lobbyTime < (TOUR ? 12f : 1.5f)) return;
		MP.Player me = MP.me();
		if (me.role == MP.Role.HERO && me.cls == null){
			MP.setMyClass(HeroClass.ROGUE);
			return;
		}
		if (!me.ready){
			MP.setMyReady(true);
			return;
		}
		if (MP.canStart() && MP.isHost()){
			log("starting");
			lobbyTime = 0;
			swapRequested = false;
			died = false;
			gameTime = 0;
			MP.requestStart();
		}
	}

	private static void closeWindows( Scene s ){
		for (Gizmo g : s.membersCopy()){
			if (g instanceof MPHud.WndResult){
				((Window) g).hide();
				log("game over, back to lobby");
				MP.endGameToLobby(true);
				return;
			}
			if (g instanceof Window && !(g instanceof com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions)){
				((Window) g).hide();
			}
		}
	}

	private static void maybeDie(){
		if (DIE_AT == null || DIE_AT.isEmpty() || died || Dungeon.hero == null || !Dungeon.hero.ready) return;
		float at;
		try { at = Float.parseFloat(DIE_AT); } catch (Exception e) { return; }
		if (gameTime >= at){
			died = true;
			log("forcing hero death");
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger h = new com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger();
			Dungeon.hero.damage(Dungeon.hero.HT * 3, h);
			if (!Dungeon.hero.isAlive()) Dungeon.fail(h);
		}
	}

	private static void maybeSwap(){
		if (SWAP_AT == null || swapRequested || !MP.isHost()) return;
		float at;
		try { at = Float.parseFloat(SWAP_AT); } catch (Exception e) { return; }
		if (gameTime >= at){
			swapRequested = true;
			log("requesting role swap");
			MP.requestSwap();
		}
	}

	public static boolean autoAcceptSwap(){
		if (ENABLED) log("auto accepting swap");
		return ENABLED;
	}

	private static void hero(){
		if (Dungeon.hero == null || Dungeon.level == null) return;
		if (!Dungeon.hero.isAlive()){
			return;
		}
		if (!Dungeon.hero.ready || GameScene.interfaceBlockingHero()) return;

		if (Dungeon.level != lastLevel){
			lastLevel = Dungeon.level;
			actionsOnLevel = 0;
			log("hero on depth " + Dungeon.depth);
		}
		actionsOnLevel++;

		//attack adjacent enemies
		for (Mob m : Dungeon.level.mobs.toArray(new Mob[0])){
			if (m.alignment == Char.Alignment.ENEMY && Dungeon.level.adjacent(m.pos, Dungeon.hero.pos)
					&& Dungeon.level.heroFOV[m.pos]){
				act(m.pos);
				return;
			}
		}

		//go down after exploring a bit
		LevelTransition exit = Dungeon.level.getTransition(LevelTransition.Type.REGULAR_EXIT);
		if (exit != null && actionsOnLevel > 25 && (Dungeon.level.visited[exit.cell()] || Dungeon.level.mapped[exit.cell()])){
			act(exit.cell());
			return;
		}

		//explore towards the frontier of what we have seen
		ArrayList<Integer> frontier = new ArrayList<>();
		int len = Dungeon.level.length();
		for (int i = 0; i < len; i++){
			if (!Dungeon.level.passable[i] || !Dungeon.level.visited[i]) continue;
			if (Dungeon.level.getTransition(i) != null) continue;
			for (int n : PathFinder.NEIGHBOURS4){
				int c = i + n;
				if (c >= 0 && c < len && !Dungeon.level.visited[c] && !Dungeon.level.solid[c]){
					frontier.add(i);
					break;
				}
			}
		}
		if (!frontier.isEmpty()){
			act(Random.element(frontier));
		} else {
			Dungeon.hero.rest(false);
		}
	}

	private static void act( int cell ){
		if (Dungeon.hero.handle(cell)){
			Dungeon.hero.next();
		}
	}

	private static float checkTimer = 0;

	//verifies that every copied character has a sprite in the right place
	private static void checkMirror(){
		checkTimer += 0.25f;
		if (checkTimer < 2f) return;
		checkTimer = 0;
		ArrayList<Char> all = new ArrayList<>();
		all.add(Dungeon.hero);
		all.addAll(Dungeon.level.mobs);
		int bad = 0;
		for (Char c : all){
			com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite sp = c.sprite;
			String problem = null;
			if (sp == null) problem = "no sprite";
			else if (sp.parent == null) problem = "sprite not in scene";
			else if (sp.ch != c) problem = "sprite linked to another char";
			else if (!sp.visible && c.isAlive()) problem = "sprite hidden";
			else if (!sp.isMoving && c.isAlive()){
				com.watabou.utils.PointF want = sp.worldToCamera(c.pos);
				if (Math.abs(want.x - sp.x) > 3 || Math.abs(want.y - sp.y) > 3){
					problem = "sprite at wrong place";
				}
			}
			if (problem != null){
				bad++;
				log("DESYNC " + c.getClass().getSimpleName() + " id=" + c.id() + " pos=" + c.pos + ": " + problem);
			}
		}
		if (Actor.findById(Dungeon.hero.id()) != Dungeon.hero){
			log("DESYNC hero not registered");
		}
		if (bad == 0) log("mirror check ok (" + all.size() + " chars)");
	}

	private static void dm(){
		if (!MPMirror.ready() || Dungeon.hero == null) return;
		checkMirror();
		staffTimer += 0.25f;

		Mob mine = MPMirror.possessed();
		if (mine == null){
			Mob best = null;
			int bestDist = Integer.MAX_VALUE;
			for (Mob m : Dungeon.level.mobs){
				if (!MPMirror.looksControllable(m)) continue;
				int d = Dungeon.level.distance(m.pos, Dungeon.hero.pos);
				if (d < bestDist){
					best = m;
					bestDist = d;
				}
			}
			if (best != null){
				log("dm possessing " + best.name() + " id=" + best.id());
				MPMirror.onCellSelected(best.pos);
				timer = 1f;
			}
			return;
		}

		//every so often test the swap staff
		if (staffTimer > 20f){
			staffTimer = 0;
			for (Mob m : Dungeon.level.mobs){
				if (m != mine && MPMirror.looksControllable(m)){
					log("dm staff swap to " + m.name());
					MPMirror.toggleStaff();
					MPMirror.onCellSelected(m.pos);
					timer = 1f;
					return;
				}
			}
		}

		if (MPMirror.turn == 2){
			//chase and attack the hero, or wait if that fails repeatedly
			dmTries++;
			if (dmTries % 3 == 0){
				MPMirror.waitTurn();
			} else {
				MPMirror.onCellSelected(Dungeon.hero.pos);
			}
			timer = 0.5f;
		} else {
			dmTries = 0;
		}
	}
}
