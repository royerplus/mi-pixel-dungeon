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

import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.IconTitle;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;
import com.watabou.noosa.Game;

import java.util.ArrayList;

//Lists rooms found on the local network, refreshed automatically
public class WndJoin extends Window {

	private static final int WIDTH = 130;
	private static final int BTN_HEIGHT = 18;
	private static final int GAP = 2;
	private static final int MAX_ROOMS = 5;

	private IconTitle title;
	private RenderedTextBlock status;
	private ArrayList<RedButton> roomButtons = new ArrayList<>();
	private RedButton btnManual;
	private RedButton btnCancel;

	private float refresh = 0;
	private String lastKey = null;
	private boolean joining = false;
	private float time = 0;

	public WndJoin(){
		super();

		Discovery.startBrowsing();

		title = new IconTitle(Icons.get(Icons.MAGNIFY), MP.txt("join_title"));
		title.setRect(0, 0, WIDTH, 0);
		add(title);

		status = PixelScene.renderTextBlock(6);
		status.maxWidth(WIDTH);
		add(status);

		btnManual = new RedButton(MP.txt("join_ip"), 7){
			@Override
			protected void onClick() {
				Game.scene().addToFront(new WndTextInput(MP.txt("join_ip"), MP.txt("join_ip_desc"),
						"", 21, false, MP.txt("join"), MP.txt("cancel")){
					@Override
					public void onSelect(boolean positive, String text) {
						if (positive){
							text = text.trim();
							int port = Net.TCP_PORT;
							if (text.contains(":")){
								try {
									port = Integer.parseInt(text.substring(text.indexOf(':') + 1).trim());
								} catch (NumberFormatException e){
									port = Net.TCP_PORT;
								}
								text = text.substring(0, text.indexOf(':')).trim();
							}
							if (!text.isEmpty()){
								join(text, port);
							}
						}
					}
				});
			}
		};
		add(btnManual);

		btnCancel = new RedButton(MP.txt("cancel"), 7){
			@Override
			protected void onClick() {
				hide();
			}
		};
		add(btnCancel);

		rebuild(new ArrayList<Discovery.Room>());
	}

	private void join( String address, int port ){
		joining = true;
		Discovery.stopBrowsing();
		MP.joinRoom(address, port);
		status.text(MP.txt("connecting", address));
		for (RedButton b : roomButtons){
			b.enable(false);
		}
		btnManual.enable(false);
		layoutAll();
	}

	private void rebuild( ArrayList<Discovery.Room> rooms ){
		for (RedButton b : roomButtons){
			b.killAndErase();
		}
		roomButtons.clear();

		int count = 0;
		for (final Discovery.Room r : rooms){
			if (count++ >= MAX_ROOMS) break;
			boolean sameVersion = r.version == Game.versionCode;
			String label = MP.txt("room", r.name);
			if (!sameVersion)   label += " " + MP.txt("room_version");
			else if (!r.open)   label += " " + MP.txt("room_full");
			RedButton b = new RedButton(label, 7){
				@Override
				protected void onClick() {
					join(r.address, r.port);
				}
			};
			b.icon(Icons.get(Icons.ENTER));
			b.enable(sameVersion && r.open && !joining);
			add(b);
			roomButtons.add(b);
		}

		if (!joining){
			status.text(rooms.isEmpty() ? MP.txt("searching") : MP.txt("found_rooms"));
		}
		layoutAll();
	}

	private void layoutAll(){
		float pos = title.bottom() + 4;
		status.setPos(0, pos);
		pos = status.bottom() + 4;
		for (RedButton b : roomButtons){
			b.setRect(0, pos, WIDTH, BTN_HEIGHT);
			pos = b.bottom() + GAP;
		}
		pos += 2;
		btnManual.setRect(0, pos, (WIDTH - GAP) / 2f, BTN_HEIGHT - 2);
		btnCancel.setRect(btnManual.right() + GAP, pos, WIDTH - btnManual.width() - GAP, BTN_HEIGHT - 2);
		resize(WIDTH, (int) btnCancel.bottom());
	}

	@Override
	public void update() {
		super.update();
		time += Game.elapsed;
		if (joining){
			return;
		}
		refresh -= Game.elapsed;
		if (refresh <= 0){
			refresh = 0.5f;
			ArrayList<Discovery.Room> rooms = Discovery.rooms();
			StringBuilder key = new StringBuilder();
			for (Discovery.Room r : rooms){
				key.append(r.address).append(r.port).append(r.name).append(r.open).append(r.version).append(';');
			}
			if (!key.toString().equals(lastKey)){
				lastKey = key.toString();
				rebuild(rooms);
			} else if (rooms.isEmpty()){
				//animated dots while searching
				int dots = (int)(time * 2) % 4;
				StringBuilder s = new StringBuilder(MP.txt("searching"));
				for (int i = 0; i < dots; i++) s.append('.');
				status.text(s.toString());
			}
		}
	}

	@Override
	public void hide() {
		super.hide();
		if (!joining){
			Discovery.stopBrowsing();
		}
	}
}
