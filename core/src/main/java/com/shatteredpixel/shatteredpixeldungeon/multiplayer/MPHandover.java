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
import com.watabou.utils.FileUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

//Packs the save files of the online slot so the game can move to the other device when roles swap
public class MPHandover {

	private static final int MAX_FILES = 200;

	public static byte[] pack( int slot ) throws IOException {
		String folder = GamesInProgress.gameFolder(slot);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bos);
		java.util.ArrayList<String> files = FileUtils.filesInDir(folder);
		java.util.ArrayList<String> valid = new java.util.ArrayList<>();
		for (String f : files){
			if (validName(f)) valid.add(f);
		}
		if (!valid.contains("game.dat")){
			throw new IOException("no save to hand over");
		}
		out.writeInt(valid.size());
		for (String f : valid){
			byte[] data = FileUtils.getFileHandle(folder + "/" + f).readBytes();
			out.writeUTF(f);
			out.writeInt(data.length);
			out.write(data);
		}
		out.flush();
		return bos.toByteArray();
	}

	public static void unpack( byte[] data ) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		int count = in.readInt();
		if (count <= 0 || count > MAX_FILES){
			throw new IOException("bad file count");
		}
		String[] names = new String[count];
		byte[][] contents = new byte[count][];
		for (int i = 0; i < count; i++){
			names[i] = in.readUTF();
			if (!validName(names[i])){
				throw new IOException("bad file name");
			}
			int len = in.readInt();
			if (len < 0 || len > data.length){
				throw new IOException("bad file size");
			}
			contents[i] = new byte[len];
			in.readFully(contents[i]);
		}

		MP.deleteSlot();
		String folder = GamesInProgress.gameFolder(MP.SLOT);
		for (int i = 0; i < count; i++){
			FileUtils.getFileHandle(folder + "/" + names[i]).writeBytes(contents[i], false);
		}
		GamesInProgress.setUnknown(MP.SLOT);
	}

	//only plain save file names, never paths
	private static boolean validName( String name ){
		return name != null && name.endsWith(".dat") && name.matches("[A-Za-z0-9_\\-]+\\.dat");
	}
}
