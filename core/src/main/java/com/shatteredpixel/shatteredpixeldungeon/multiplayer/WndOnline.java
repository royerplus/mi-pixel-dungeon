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

import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.HeroSelectScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.IconTitle;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndMessage;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;
import com.watabou.noosa.Game;

//Entry point from the title screen: create a room or join one
public class WndOnline extends Window {

	private static final int WIDTH = 130;
	private static final int BTN_HEIGHT = 20;
	private static final int GAP = 2;

	private RedButton btnName;

	public WndOnline(){
		super();

		IconTitle title = new IconTitle(Icons.get(Icons.SHUFFLE), MP.txt("online_title"));
		title.setRect(0, 0, WIDTH, 0);
		add(title);

		RenderedTextBlock desc = PixelScene.renderTextBlock(MP.txt("online_desc"), 6);
		desc.maxWidth(WIDTH);
		desc.setPos(0, title.bottom() + 4);
		add(desc);

		float pos = desc.bottom() + 6;

		btnName = new RedButton(""){
			@Override
			protected void onClick() {
				Game.scene().addToFront(new WndTextInput(MP.txt("name_title"), MP.txt("name_desc"),
						MP.myName(), 16, false, MP.txt("name_set"), MP.txt("cancel")){
					@Override
					public void onSelect(boolean positive, String text) {
						if (positive){
							text = text.replace("|", "").trim();
							if (!text.isEmpty()){
								MP.setMyName(text);
								updateName();
							}
						}
					}
				});
			}
		};
		btnName.icon(Icons.get(Icons.INFO));
		btnName.setRect(0, pos, WIDTH, BTN_HEIGHT);
		add(btnName);
		updateName();
		pos = btnName.bottom() + GAP + 4;

		RedButton btnCreate = new RedButton(MP.txt("create")){
			@Override
			protected void onClick() {
				hide();
				if (MP.hostRoom()){
					HeroSelectScene.onlineMode = true;
					GamesInProgress.selectedClass = MP.defaultClass();
					ShatteredPixelDungeon.switchNoFade(HeroSelectScene.class);
				} else {
					Game.scene().addToFront(new WndMessage(MP.txt("host_failed")));
				}
			}
		};
		btnCreate.icon(Icons.get(Icons.ENTER));
		btnCreate.textColor(Window.TITLE_COLOR);
		btnCreate.setRect(0, pos, WIDTH, BTN_HEIGHT);
		add(btnCreate);
		pos = btnCreate.bottom() + GAP;

		RedButton btnJoin = new RedButton(MP.txt("join")){
			@Override
			protected void onClick() {
				hide();
				Game.scene().addToFront(new WndJoin());
			}
		};
		btnJoin.icon(Icons.get(Icons.MAGNIFY));
		btnJoin.setRect(0, pos, WIDTH, BTN_HEIGHT);
		add(btnJoin);
		pos = btnJoin.bottom();

		resize(WIDTH, (int) pos);
	}

	private void updateName(){
		btnName.text(MP.txt("name_button", MP.myName()));
	}
}
