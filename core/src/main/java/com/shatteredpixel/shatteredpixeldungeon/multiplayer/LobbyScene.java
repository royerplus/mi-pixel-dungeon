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

import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.HeroSelectScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.HeroSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.SkeletonSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.IconButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.TitleBackground;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.IconTitle;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTitledMessage;
import com.watabou.input.GameAction;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.noosa.NinePatch;
import com.watabou.noosa.audio.Sample;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.watabou.utils.RectF;

import java.util.ArrayList;

//The online lobby: one row per player, a ready button and a start button
public class LobbyScene extends PixelScene {

	private static final int ROW_WIDTH = 124;
	private static final int ROW_HEIGHT = 32;

	private PlayerRow rowMe;
	private PlayerRow rowOther;
	private StyledButton btnSwap;
	private StyledButton btnReady;
	private StyledButton btnStart;
	private RenderedTextBlock info;

	private int shownVersion = -1;

	@Override
	public void create() {
		super.create();

		//leftovers from a finished online game are never resumed
		MPMirror.clearWorldIfCopy();
		Dungeon.hero = null;
		if (!MP.inGame()){
			MP.deleteSlot();
		}

		if (!MP.sessionActive()){
			ShatteredPixelDungeon.switchNoFade(com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene.class);
			return;
		}

		uiCamera.visible = false;

		int w = Camera.main.width;
		int h = Camera.main.height;
		RectF insets = getCommonInsets();

		TitleBackground bg = new TitleBackground(w, h);
		add(bg);

		w -= insets.left + insets.right;
		h -= insets.top + insets.bottom;

		IconButton btnExit = new IconButton(Icons.EXIT.get()){
			@Override
			protected void onClick() {
				confirmLeave();
			}

			@Override
			public GameAction keyAction() {
				return GameAction.BACK;
			}
		};
		btnExit.setRect(insets.left + w - 20, insets.top, 20, 20);
		add(btnExit);

		IconTitle title = new IconTitle(Icons.get(Icons.SHUFFLE), MP.txt("lobby_title"));
		title.setSize(200, 0);
		title.setPos(insets.left + (w - title.reqWidth()) / 2f, insets.top + (20 - title.height()) / 2f);
		align(title);
		add(title);

		float total = ROW_HEIGHT * 2 + 16 + 4 + 22 + 16;
		float top = Math.max(title.bottom() + 6, insets.top + (h - total) / 2f);
		float left = insets.left + (w - ROW_WIDTH) / 2f;

		rowMe = new PlayerRow(true);
		rowMe.setRect(left, top, ROW_WIDTH, ROW_HEIGHT);
		add(rowMe);

		btnSwap = new StyledButton(Chrome.Type.TOAST_TR, MP.txt("swap_roles"), 6){
			@Override
			protected void onClick() {
				if (!MP.peerPresent()){
					MP.notice(MP.txt("need_two"));
					return;
				}
				MP.requestSwap();
			}
		};
		btnSwap.icon(Icons.get(Icons.SHUFFLE));
		btnSwap.setSize(btnSwap.reqWidth() + 6, 14);
		btnSwap.setPos(left + (ROW_WIDTH - btnSwap.width()) / 2f, rowMe.bottom() + 2);
		align(btnSwap);
		add(btnSwap);

		rowOther = new PlayerRow(false);
		rowOther.setRect(left, btnSwap.bottom() + 2, ROW_WIDTH, ROW_HEIGHT);
		add(rowOther);

		float btnW = (ROW_WIDTH - 2) / 2f;
		btnReady = new StyledButton(Chrome.Type.GREY_BUTTON_TR, ""){
			@Override
			protected void onClick() {
				if (MP.myRole() == MP.Role.HERO && MP.me().cls == null){
					MP.notice(MP.txt("pick_hero_first"));
					return;
				}
				MP.setMyReady(!MP.me().ready);
			}
		};
		btnReady.setRect(left, rowOther.bottom() + 6, btnW, 22);
		add(btnReady);

		btnStart = new StyledButton(Chrome.Type.GREY_BUTTON_TR, MP.txt("start")){
			@Override
			protected void onClick() {
				if (MP.canStart()){
					MP.requestStart();
				}
			}
		};
		btnStart.icon(Icons.get(Icons.ENTER));
		btnStart.setRect(btnReady.right() + 2, btnReady.top(), btnW, 22);
		add(btnStart);

		info = renderTextBlock(6);
		info.maxWidth(ROW_WIDTH + 40);
		add(info);
		String infoText;
		if (MP.isHost()){
			ArrayList<String> ips = Discovery.localAddresses();
			infoText = ips.isEmpty() ? MP.txt("lobby_info_host_noip") : MP.txt("lobby_info_host", ips.get(0));
		} else {
			infoText = MP.txt("lobby_info_guest");
		}
		info.text(infoText);
		info.hardlight(0xAAAAAA);
		info.setPos(insets.left + (w - info.width()) / 2f, btnReady.bottom() + 6);
		align(info);

		refresh();

		fadeIn();

		if (MP.pendingLobbyMessage != null){
			MP.notice(MP.pendingLobbyMessage);
			MP.pendingLobbyMessage = null;
		}
	}

	private void refresh(){
		shownVersion = MP.lobbyVersion;
		rowMe.set(MP.me(), true);
		rowOther.set(MP.peer(), false);

		MP.Player me = MP.me();
		btnReady.text(MP.txt("ready", MP.readyCount(), MP.presentCount()));
		btnReady.icon(Icons.get(me.ready ? Icons.CHECKED : Icons.UNCHECKED));
		btnReady.textColor(me.ready ? 0x44FF44 : 0xFFFFFF);

		boolean can = MP.canStart();
		btnStart.enable(can);
		btnStart.alpha(can ? 1f : 0.3f);
		btnStart.textColor(can ? Window.TITLE_COLOR : 0xFFFFFF);

		btnSwap.enable(MP.peerPresent());
		btnSwap.alpha(MP.peerPresent() ? 1f : 0.4f);
	}

	@Override
	public void update() {
		super.update();
		if (!MP.sessionActive()) return;
		if (shownVersion != MP.lobbyVersion){
			refresh();
		}
	}

	private void confirmLeave(){
		add(new WndOptions(Icons.get(Icons.WARNING), MP.txt("leave"),
				MP.isHost() ? MP.txt("leave_confirm_host") : MP.txt("leave_confirm"),
				MP.txt("leave"), MP.txt("cancel")){
			@Override
			protected void onSelect(int index) {
				if (index == 0) MP.leave(null);
			}
		});
	}

	@Override
	protected void onBackPressed() {
		confirmLeave();
	}

	private static class PlayerRow extends Button {

		private final boolean mine;

		private NinePatch bg;
		private Image avatar;
		private RenderedTextBlock name;
		private RenderedTextBlock sub;
		private Image readyIcon;

		private MP.Player player;
		private float time = 0;

		PlayerRow( boolean mine ){
			super();
			this.mine = mine;
		}

		@Override
		protected void createChildren() {
			super.createChildren();
			bg = Chrome.get(Chrome.Type.TOAST_TR);
			add(bg);
			name = PixelScene.renderTextBlock(9);
			add(name);
			sub = PixelScene.renderTextBlock(6);
			add(sub);
		}

		void set( MP.Player p, boolean isMe ){
			player = p;
			if (avatar != null){
				avatar.killAndErase();
				avatar = null;
			}
			if (readyIcon != null){
				readyIcon.killAndErase();
				readyIcon = null;
			}

			if (!p.present){
				avatar = HeroSprite.avatar(HeroClass.WARRIOR, 0);
				avatar.brightness(0f);
				avatar.alpha(0.8f);
				name.text(MP.txt("waiting_player"));
				name.hardlight(0x999999);
				sub.text(p.role == MP.Role.HERO ? MP.txt("role_hero") : MP.txt("role_dm"));
				sub.hardlight(0x777777);
			} else {
				if (p.role == MP.Role.HERO){
					if (p.cls != null){
						avatar = HeroSprite.avatar(p.cls, 1);
						sub.text(MP.txt("role_hero") + ": " + Messages.titleCase(p.cls.title()));
					} else {
						avatar = HeroSprite.avatar(HeroClass.WARRIOR, 0);
						avatar.brightness(0.3f);
						sub.text(isMe ? MP.txt("tap_to_pick") : MP.txt("picking_hero"));
					}
				} else {
					SkeletonSprite s = new SkeletonSprite();
					s.idle();
					avatar = s;
					sub.text(MP.txt("role_dm"));
				}
				name.text(p.name + (isMe ? " " + MP.txt("you") : ""));
				name.hardlight(isMe ? Window.TITLE_COLOR : 0xFFFFFF);
				sub.hardlight(p.role == MP.Role.HERO ? 0x88CCFF : 0xFF8866);
				readyIcon = Icons.get(p.ready ? Icons.CHECKED : Icons.UNCHECKED);
				add(readyIcon);
			}
			add(avatar);
			layout();
		}

		@Override
		protected void layout() {
			super.layout();
			bg.x = x;
			bg.y = y;
			bg.size(width, height);

			if (avatar != null){
				avatar.x = x + 8 + (16 - avatar.width()) / 2f;
				avatar.y = y + (height - avatar.height()) / 2f;
				PixelScene.align(avatar);
			}

			float textLeft = x + 30;
			name.maxWidth((int)(width - 30 - 20));
			sub.maxWidth((int)(width - 30 - 20));
			name.setPos(textLeft, y + (height - name.height() - sub.height() - 2) / 2f);
			PixelScene.align(name);
			sub.setPos(textLeft, name.bottom() + 2);
			PixelScene.align(sub);

			if (readyIcon != null){
				readyIcon.x = x + width - 8 - readyIcon.width();
				readyIcon.y = y + (height - readyIcon.height()) / 2f;
				PixelScene.align(readyIcon);
			}
		}

		@Override
		public void update() {
			super.update();
			if (player != null && !player.present){
				time += Game.elapsed;
				if (avatar != null){
					avatar.alpha(0.5f + 0.3f * (float) Math.sin(time * 3));
				}
			}
		}

		@Override
		protected void onPointerDown() {
			bg.brightness(1.2f);
			Sample.INSTANCE.play(Assets.Sounds.CLICK);
		}

		@Override
		protected void onPointerUp() {
			bg.resetColor();
		}

		@Override
		protected void onClick() {
			if (player == null) return;
			if (mine){
				if (player.role == MP.Role.HERO){
					HeroSelectScene.onlineMode = true;
					GamesInProgress.selectedClass = player.cls != null ? player.cls : MP.defaultClass();
					ShatteredPixelDungeon.switchNoFade(HeroSelectScene.class);
				} else {
					Game.scene().addToFront(new WndTitledMessage(new SkeletonSprite(),
							MP.txt("role_dm"), MP.txt("dm_help")));
				}
			} else if (player.present && player.role == MP.Role.DM){
				Game.scene().addToFront(new WndTitledMessage(new SkeletonSprite(),
						MP.txt("role_dm"), MP.txt("dm_help")));
			}
		}
	}
}
