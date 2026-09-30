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
import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.HeroSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.HealthBar;
import com.shatteredpixel.shatteredpixeldungeon.ui.IconButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.IconTitle;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoMob;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.input.GameAction;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.noosa.NinePatch;
import com.watabou.noosa.audio.Sample;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.RectF;

//Extra interface elements for online games, added on top of the regular game scene
public class MPHud {

	// ***** hero device *****

	public static void attachHero( GameScene scene, RectF insets, float top ){
		Camera ui = PixelScene.uiCamera;

		TurnLabel turn = new TurnLabel(false);
		turn.camera = ui;
		turn.setTop(top + (PixelScene.landscape() ? 24 : 44));
		scene.add(turn);

		SwapButton swap = new SwapButton();
		swap.camera = ui;
		swap.setRect(ui.width - insets.right - 22, top + 40, 20, 20);
		scene.add(swap);

		GameScene.addOverlay(new PossessionMarker(false));
	}

	// ***** enemy player's device *****

	public static void attachMirror( GameScene scene, RectF insets, float top ){
		Camera ui = PixelScene.uiCamera;

		DMPanel panel = new DMPanel();
		panel.camera = ui;
		panel.setPos(insets.left + 1, top + 1);
		scene.add(panel);

		IconButton menu = new IconButton(Icons.get(Icons.PREFS)){
			@Override
			protected void onClick() {
				GameScene.show(new WndGame());
			}

			@Override
			protected String hoverText() {
				return MP.txt("menu");
			}
		};
		menu.camera = ui;
		menu.setRect(ui.width - insets.right - 21, top + 1, 20, 20);
		scene.add(menu);

		SwapButton swap = new SwapButton();
		swap.camera = ui;
		swap.setRect(menu.left() - 21, top + 1, 20, 20);
		scene.add(swap);

		DepthLabel depth = new DepthLabel();
		depth.camera = ui;
		depth.setRect(swap.left() - 22, top + 1, 20, 20);
		scene.add(depth);

		TurnLabel turn = new TurnLabel(true);
		turn.camera = ui;
		turn.setTop(panel.bottom() + 3);
		scene.add(turn);

		DMToolbar bar = new DMToolbar();
		bar.camera = ui;
		float w = Math.min(ui.width - insets.left - insets.right - 4, 220);
		bar.setRect(insets.left + (ui.width - insets.left - insets.right - w) / 2f,
				ui.height - insets.bottom - 24, w, 22);
		scene.add(bar);

		GameScene.addOverlay(new PossessionMarker(true));

		if (MPMirror.gameOverWon != null){
			showResult(MPMirror.gameOverWon);
		}
	}

	public static void showResult( boolean heroWon ){
		if (Game.scene() instanceof GameScene){
			GameScene.show(new WndResult(heroWon));
		}
	}

	// ***** components *****

	//text at the top center showing whose turn it is
	private static class TurnLabel extends Component {

		private final boolean mirror;
		private NinePatch bg;
		private RenderedTextBlock text;
		private String last = null;
		private float top;
		private float time;

		TurnLabel( boolean mirror ){
			super();
			this.mirror = mirror;
		}

		@Override
		protected void createChildren() {
			bg = Chrome.get(Chrome.Type.TOAST_TR);
			add(bg);
			text = PixelScene.renderTextBlock(7);
			add(text);
		}

		void setTop( float top ){
			this.top = top;
		}

		private String current(){
			if (!MP.inGame() || MP.gameFinished()) return null;
			if (mirror){
				if (!MPMirror.ready()) return null;
				switch (MPMirror.turn){
					case 2:
						long left = Math.max(0, MPMirror.waitDeadline - System.currentTimeMillis()) / 1000;
						return MP.txt("turn_yours", left);
					case 1:
						return MP.txt("turn_hero", MP.heroPlayer().name);
					case 3:
						return MP.txt("turn_transition");
					default:
						return null;
				}
			} else {
				if (MPAuthority.waitingForDM){
					long left = Math.max(0, MPAuthority.TURN_TIME - (System.currentTimeMillis() - MPAuthority.waitStart)) / 1000;
					return MP.txt("turn_dm", MP.dmPlayer().name, left);
				}
				return null;
			}
		}

		@Override
		public void update() {
			super.update();
			String s = current();
			if (s == null){
				bg.visible = text.visible = false;
				last = null;
				return;
			}
			bg.visible = text.visible = true;
			if (!s.equals(last)){
				last = s;
				text.text(s);
				float w = text.width() + bg.marginHor();
				float h = text.height() + bg.marginVer();
				setRect((camera().width - w) / 2f, top, w, h);
			}
			boolean urgent = mirror && MPMirror.turn == 2;
			if (urgent){
				time += Game.elapsed;
				text.hardlight(0.5f + 0.5f * (float)Math.abs(Math.sin(time * 4)) > 0.75f ? 0xFFFF44 : 0xFFFFFF);
			} else {
				text.resetColor();
			}
		}

		@Override
		protected void layout() {
			bg.x = x;
			bg.y = y;
			bg.size(width, height);
			text.setPos(x + bg.marginLeft(), y + bg.marginTop());
			PixelScene.align(text);
		}
	}

	private static class SwapButton extends IconButton {

		SwapButton(){
			super(Icons.get(Icons.SHUFFLE));
		}

		@Override
		protected void onClick() {
			if (!MP.peerPresent() || MP.gameFinished()) return;
			GameScene.show(new WndOptions(Icons.get(Icons.SHUFFLE), MP.txt("swap_title"), MP.txt("swap_confirm"),
					MP.txt("swap_ask"), MP.txt("cancel")){
				@Override
				protected void onSelect(int index) {
					if (index == 0) MP.requestSwap();
				}
			});
		}

		@Override
		protected String hoverText() {
			return MP.txt("swap_roles");
		}
	}

	private static class DepthLabel extends Component {
		private Image icon;
		private RenderedTextBlock text;
		private int shown = -1;

		@Override
		protected void createChildren() {
			icon = Icons.get(Icons.STAIRS);
			add(icon);
			text = PixelScene.renderTextBlock(6);
			add(text);
		}

		@Override
		public void update() {
			super.update();
			if (shown != Dungeon.depth){
				shown = Dungeon.depth;
				text.text(Integer.toString(Dungeon.depth));
				layout();
			}
		}

		@Override
		protected void layout() {
			icon.x = x + (width - icon.width()) / 2f;
			icon.y = y + 2;
			PixelScene.align(icon);
			text.setPos(x + (width - text.width()) / 2f, icon.y + icon.height() + 1);
			PixelScene.align(text);
		}
	}

	//top left panel of the enemy player: their creature and the hero
	private static class DMPanel extends Button {

		private static final int WIDTH = 104;

		private NinePatch bg;

		private Image mobImage;
		private RenderedTextBlock mobName;
		private HealthBar mobHP;
		private RenderedTextBlock hint;

		private Image heroImage;
		private RenderedTextBlock heroName;
		private HealthBar heroHP;

		private int shownMob = -1;
		private Class<?> shownMobClass = null;
		private int shownTier = -1;

		@Override
		protected void createChildren() {
			super.createChildren();
			bg = Chrome.get(Chrome.Type.TOAST_TR);
			add(bg);

			mobName = PixelScene.renderTextBlock(7);
			add(mobName);
			mobHP = new HealthBar();
			add(mobHP);
			hint = PixelScene.renderTextBlock(6);
			hint.text(MP.txt("hint_pick"));
			hint.hardlight(0xFFCC66);
			add(hint);

			heroName = PixelScene.renderTextBlock(6);
			add(heroName);
			heroHP = new HealthBar();
			add(heroHP);

			width = WIDTH;
			height = 38;
		}

		@Override
		protected void layout() {
			super.layout();
			bg.x = x;
			bg.y = y;
			bg.size(width, height);

			float rowTop = y + 3;
			if (mobImage != null){
				mobImage.x = x + 4 + (16 - Math.min(16, mobImage.width())) / 2f;
				mobImage.y = rowTop + (16 - Math.min(16, mobImage.height())) / 2f;
				PixelScene.align(mobImage);
			}
			mobName.maxWidth((int)width - 26);
			mobName.setPos(x + 23, rowTop);
			PixelScene.align(mobName);
			mobHP.setRect(x + 23, mobName.bottom() + 2, width - 28, 2);
			hint.maxWidth((int)width - 8);
			hint.setPos(x + 4, rowTop + 1);
			PixelScene.align(hint);

			float heroTop = y + 21;
			if (heroImage != null){
				heroImage.x = x + 6;
				heroImage.y = heroTop;
				PixelScene.align(heroImage);
			}
			heroName.maxWidth((int)width - 26);
			heroName.setPos(x + 23, heroTop);
			PixelScene.align(heroName);
			heroHP.setRect(x + 23, heroName.bottom() + 2, width - 28, 2);
		}

		@Override
		public void update() {
			super.update();
			if (!MPMirror.ready()) return;

			Mob m = MPMirror.possessed();
			int id = m == null ? 0 : m.id();
			if (id != shownMob || (m != null && m.getClass() != shownMobClass)){
				shownMob = id;
				shownMobClass = m == null ? null : m.getClass();
				if (mobImage != null){
					mobImage.killAndErase();
					mobImage = null;
				}
				if (m != null){
					CharSprite s = m.sprite();
					s.idle();
					if (s.height() > 16 || s.width() > 16){
						float scale = 16f / Math.max(s.width(), s.height());
						s.scale.set(scale);
					}
					mobImage = s;
					add(mobImage);
					mobName.text(Messages.titleCase(m.name()));
				}
				mobName.visible = mobHP.visible = m != null;
				hint.visible = m == null;
				layout();
			}
			if (m != null){
				mobHP.level(m);
			}

			Hero h = Dungeon.hero;
			if (h != null){
				if (heroImage == null || h.tier() != shownTier){
					shownTier = h.tier();
					if (heroImage != null) heroImage.killAndErase();
					heroImage = HeroSprite.avatar(h);
					add(heroImage);
					layout();
				}
				String t = MP.txt("hero_line", h.HP, h.HT);
				if (!t.equals(heroName.text())){
					heroName.text(t);
				}
				heroHP.level(h);
			}
		}

		@Override
		protected void onClick() {
			Mob m = MPMirror.possessed();
			if (m != null){
				GameScene.show(new WndInfoMob(m));
			} else {
				MPMirror.centerOn(true);
			}
		}
	}

	//bottom bar of the enemy player
	private static class DMToolbar extends Component {

		private StyledButton btnWait;
		private StyledButton btnStaff;
		private StyledButton btnRelease;
		private StyledButton btnCenter;
		private boolean lastStaff = false;

		@Override
		protected void createChildren() {
			btnWait = new StyledButton(Chrome.Type.GREY_BUTTON_TR, MP.txt("btn_wait"), 7){
				@Override
				protected void onClick() {
					MPMirror.waitTurn();
				}

				@Override
				public GameAction keyAction() {
					return com.shatteredpixel.shatteredpixeldungeon.SPDAction.WAIT;
				}
			};
			btnWait.icon(Icons.get(Icons.SLEEP));
			add(btnWait);

			btnStaff = new StyledButton(Chrome.Type.GREY_BUTTON_TR, MP.txt("btn_staff"), 7){
				@Override
				protected void onClick() {
					MPMirror.toggleStaff();
				}
			};
			btnStaff.icon(new ItemSprite(ItemSpriteSheet.WAND_CORRUPTION));
			add(btnStaff);

			btnRelease = new StyledButton(Chrome.Type.GREY_BUTTON_TR, MP.txt("btn_release"), 7){
				@Override
				protected void onClick() {
					MPMirror.release();
				}
			};
			btnRelease.icon(Icons.get(Icons.CLOSE));
			add(btnRelease);

			btnCenter = new StyledButton(Chrome.Type.GREY_BUTTON_TR, MP.txt("btn_center"), 7){
				private boolean onHero = false;
				@Override
				protected void onClick() {
					onHero = MPMirror.possessed() == null || !onHero;
					MPMirror.centerOn(onHero);
				}
			};
			btnCenter.icon(Icons.get(Icons.TARGET));
			add(btnCenter);
		}

		@Override
		protected void layout() {
			float w = (width - 3) / 4f;
			btnWait.setRect(x, y, w, height);
			btnStaff.setRect(btnWait.right() + 1, y, w, height);
			btnRelease.setRect(btnStaff.right() + 1, y, w, height);
			btnCenter.setRect(btnRelease.right() + 1, y, w, height);
			PixelScene.align(btnWait);
			PixelScene.align(btnStaff);
			PixelScene.align(btnRelease);
			PixelScene.align(btnCenter);
		}

		@Override
		public void update() {
			super.update();
			boolean has = MPMirror.possessedId != 0 && !MP.gameFinished();
			btnWait.enable(has);
			btnStaff.enable(has);
			btnRelease.enable(has);
			btnWait.alpha(has ? 1f : 0.4f);
			btnStaff.alpha(has ? 1f : 0.4f);
			btnRelease.alpha(has ? 1f : 0.4f);
			if (lastStaff != MPMirror.staffMode){
				lastStaff = MPMirror.staffMode;
				btnStaff.textColor(lastStaff ? Window.TITLE_COLOR : 0xFFFFFF);
			}
		}
	}

	//floating marker over the creature controlled by the enemy player
	private static class PossessionMarker extends Image {

		private final boolean mirror;
		private float time = 0;

		PossessionMarker( boolean mirror ){
			super(Icons.get(Icons.TARGET));
			this.mirror = mirror;
			hardlight(mirror ? 0xFFDD44 : 0xFF3333);
		}

		@Override
		public void update() {
			super.update();
			Mob m = mirror ? MPMirror.possessed() : MPAuthority.possessed();
			CharSprite s = m == null ? null : m.sprite;
			if (s == null || s.parent == null || !s.visible || !m.isAlive()){
				visible = false;
				return;
			}
			visible = true;
			time += Game.elapsed;
			x = s.x + (s.width() - width()) / 2f;
			y = s.y - height() - 1 - (float)Math.abs(Math.sin(time * 3)) * 2f;
			alpha(0.6f + 0.4f * (float)Math.abs(Math.sin(time * 2)));
		}
	}

	//shown to both players when the game ends
	public static class WndResult extends Window {

		private static final int WIDTH = 120;

		public WndResult( boolean heroWon ){
			super();
			Image icon = heroWon ? HeroSprite.avatar(MP.heroPlayer().cls != null ? MP.heroPlayer().cls
					: com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass.WARRIOR, 6)
					: new com.shatteredpixel.shatteredpixeldungeon.sprites.SkeletonSprite();
			IconTitle title = new IconTitle(icon, heroWon ? MP.txt("result_hero") : MP.txt("result_dm"));
			title.setRect(0, 0, WIDTH, 0);
			add(title);

			RenderedTextBlock msg = PixelScene.renderTextBlock(heroWon ? MP.txt("result_hero_desc") : MP.txt("result_dm_desc"), 6);
			msg.maxWidth(WIDTH);
			msg.setPos(0, title.bottom() + 4);
			add(msg);

			RedButton btn = new RedButton(MP.txt("to_lobby")){
				@Override
				protected void onClick() {
					hide();
					MP.endGameToLobby(true);
				}
			};
			btn.icon(Icons.get(Icons.ENTER));
			btn.setRect(0, msg.bottom() + 6, WIDTH, 20);
			add(btn);

			resize(WIDTH, (int) btn.bottom());
			Sample.INSTANCE.play(heroWon ? Assets.Sounds.LEVELUP : Assets.Sounds.DEATH);
		}

		@Override
		public void onBackPressed() {
			//must use the button
		}
	}
}
