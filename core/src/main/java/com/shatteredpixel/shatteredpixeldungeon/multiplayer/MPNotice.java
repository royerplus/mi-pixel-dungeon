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
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.NinePatch;
import com.watabou.noosa.Scene;
import com.watabou.noosa.ui.Component;

//Short message shown at the top of the screen for a few seconds, works in any scene
public class MPNotice extends Component {

	private static final float DURATION = 3.5f;
	private static final float FADE = 0.5f;

	private static MPNotice current;

	private NinePatch bg;
	private RenderedTextBlock text;
	private float life = DURATION;

	private MPNotice( String msg, Camera cam ){
		super();
		camera = cam;
		text.text(msg);
		text.maxWidth(Math.min(180, (int)(cam.width * 0.9f)));
		layoutSelf();
	}

	@Override
	protected void createChildren() {
		bg = Chrome.get(Chrome.Type.TOAST_TR);
		add(bg);
		text = PixelScene.renderTextBlock(7);
		add(text);
	}

	private void layoutSelf(){
		float w = text.width() + bg.marginHor();
		float h = text.height() + bg.marginVer();
		setRect((camera.width - w) / 2f, 24, w, h);
	}

	@Override
	protected void layout() {
		bg.x = x;
		bg.y = y;
		bg.size(width, height);
		text.setPos(x + bg.marginLeft(), y + bg.marginTop());
		PixelScene.align(text);
	}

	@Override
	public void update() {
		super.update();
		life -= Game.elapsed;
		float a = Math.min(1f, life / FADE);
		if (life <= 0){
			killAndErase();
			if (current == this) current = null;
		} else {
			bg.alpha(a);
			text.alpha(a);
		}
	}

	public static void show( String msg ){
		if (msg == null || msg.isEmpty()) return;
		Scene s = Game.scene();
		if (!(s instanceof PixelScene)) return;
		if (current != null){
			current.killAndErase();
		}
		Camera cam = PixelScene.uiCamera != null && PixelScene.uiCamera.visible ? PixelScene.uiCamera : Camera.main;
		current = new MPNotice(msg, cam);
		s.addToFront(current);
	}
}
