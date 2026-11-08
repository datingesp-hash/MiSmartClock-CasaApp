package com.archie.casa;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

/** config.json lives in the app's private files dir (pushed with root over adb). */
final class Config {
    static final class Input {
        String name, icon, service;
    }

    static final class Item {
        String id, name, icon, domain;
        int color; // 0 = automatic
        final ArrayList<Input> inputs = new ArrayList<>();
    }

    static final class Room {
        String name;
        final ArrayList<Item> items = new ArrayList<>();
    }

    String url, token;
    String receiverName = "Mi Smart Clock";
    final ArrayList<Room> rooms = new ArrayList<>();
    /** Lights of the clock's room: while any is on the screen stays on (dimmed) instead of going off. */
    final ArrayList<String> roomLights = new ArrayList<>();

    static Config load(File f) throws Exception {
        JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        Config c = new Config();
        String u = o.getString("url").trim();
        if (u.startsWith("http")) {
            u = "ws" + u.substring(4);
            int p = u.indexOf('/', u.indexOf("//") + 2);
            if (p > 0) u = u.substring(0, p);
            u += "/api/websocket";
        }
        c.url = u;
        c.token = o.getString("token").trim();
        c.receiverName = o.optString("receiver_name", c.receiverName);
        String lang = o.optString("language", "");
        if (!lang.isEmpty()) Ui.LOC = java.util.Locale.forLanguageTag(lang);
        JSONArray rs = o.getJSONArray("rooms");
        for (int i = 0; i < rs.length(); i++) {
            JSONObject r = rs.getJSONObject(i);
            Room room = new Room();
            room.name = r.getString("name");
            JSONArray es = r.getJSONArray("entities");
            for (int j = 0; j < es.length(); j++) {
                JSONObject e = es.getJSONObject(j);
                Item it = new Item();
                it.id = e.getString("id");
                it.domain = it.id.substring(0, it.id.indexOf('.'));
                it.name = e.optString("name", it.id);
                it.icon = e.optString("icon", "");
                String col = e.optString("color", "");
                if (!col.isEmpty()) it.color = (int) Long.parseLong(col.replace("#", "").length() == 6 ? "FF" + col.replace("#", "") : col.replace("#", ""), 16);
                JSONArray ins = e.optJSONArray("inputs");
                if (ins != null) {
                    for (int k = 0; k < ins.length(); k++) {
                        JSONObject x = ins.getJSONObject(k);
                        Input in = new Input();
                        in.name = x.getString("name");
                        in.icon = x.optString("icon", "monitor");
                        in.service = x.getString("service");
                        it.inputs.add(in);
                    }
                }
                room.items.add(it);
            }
            c.rooms.add(room);
        }
        JSONArray rl = o.optJSONArray("room_lights");
        if (rl != null) for (int i = 0; i < rl.length(); i++) c.roomLights.add(rl.getString(i));
        else if (!c.rooms.isEmpty()) // default: the lights of the first room
            for (Item it : c.rooms.get(0).items) if ("light".equals(it.domain)) c.roomLights.add(it.id);
        return c;
    }
}
