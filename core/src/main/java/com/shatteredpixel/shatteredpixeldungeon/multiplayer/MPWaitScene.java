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
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.TitleBackground;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.utils.RectF;

//Shown on the enemy player's device while the hero device prepares the floor
public class MPWaitScene extends PixelScene {

	private static String message = "";

	private RenderedTextBlock text;
	private float time = 0;
	private float resyncTimer = 0;
	private int lastDots = -1;

	public static void show( String msg ){
		message = msg;
		ShatteredPixelDungeon.switchNoFade(MPWaitScene.class);
	}

	@Override
	public void create() {
		super.create();

		uiCamera.visible = false;

		int w = Camera.main.width;
		int h = Camera.main.height;
		RectF insets = getCommonInsets();

		TitleBackground bg = new TitleBackground(w, h);
		add(bg);

		w -= insets.left + insets.right;
		h -= insets.top + insets.bottom;

		text = renderTextBlock(message, 9);
		text.maxWidth((int)Math.min(200, w - 20));
		text.setPos(insets.left + (w - text.width()) / 2f, insets.top + h / 2f - text.height() - 10);
		align(text);
		add(text);

		StyledButton leave = new StyledButton(Chrome.Type.GREY_BUTTON_TR, MP.txt("leave")){
			@Override
			protected void onClick() {
				confirmLeave();
			}
		};
		leave.icon(Icons.get(Icons.EXIT));
		leave.setSize(Math.max(80, leave.reqWidth() + 8), 20);
		leave.setPos(insets.left + (w - leave.width()) / 2f, insets.top + h / 2f + 20);
		align(leave);
		add(leave);

		fadeIn();
	}

	private void confirmLeave(){
		add(new WndOptions(Icons.get(Icons.WARNING), MP.txt("leave"), MP.txt("leave_confirm"),
				MP.txt("leave"), MP.txt("cancel")){
			@Override
			protected void onSelect(int index) {
				if (index == 0) MP.leave(null);
			}
		});
	}

	@Override
	public void update() {
		super.update();
		time += Game.elapsed;
		int dots = (int)(time * 2) % 4;
		if (dots != lastDots){
			lastDots = dots;
			StringBuilder s = new StringBuilder(message);
			for (int i = 0; i < dots; i++) s.append('.');
			text.text(s.toString());
		}

		//if the floor does not arrive for a while, ask for it again
		if (MP.mirror){
			resyncTimer += Game.elapsed;
			if (resyncTimer > 8f){
				resyncTimer = 0;
				Net.send(MP.MSG_RESYNC);
			}
		}
	}

	@Override
	protected void onBackPressed() {
		confirmLeave();
	}
}
