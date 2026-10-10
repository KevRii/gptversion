package com.exambook;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.security.keystore.*;
import android.util.Base64;
import android.view.*;
import android.widget.*;

import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 10;
    private static final int EXPORT = 11;
    private static final int IMPORT = 12;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private JSONObject db;
    private LinearLayout root, toolbar, body, shelf, messages, attachments;
    private ScrollView chatScroll;
    private EditText input;
    private TextView status;
    private Button sendButton;
    private String folder = "", selected = "", bookId = "";
    private boolean flowMode = false;
    private volatile boolean busy = false;
    private volatile boolean cancelled = false;
    private volatile HttpURLConnection connection;
    private final ArrayList<String> pending = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
    private long lastTap;
    private String lastTapId = "";

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static JSONObject obj() {
        return new JSONObject();
    }

    private static JSONObject put(JSONObject o, String k, Object v) {
        try { o.put(k, v); }
        catch (JSONException e) { throw new IllegalStateException(e); }
        return o;
    }

    private static JSONArray array(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        if (a == null) {
            a = new JSONArray();
            put(o, key, a);
        }
        return a;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(Color.rgb(36, 47, 52));
        return t;
    }

    private Button button(String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(15);
        return e;
    }

    private void labelled(LinearLayout box, String label, View view) {
        TextView t = text(label, 13);
        t.setPadding(0, dp(12), 0, dp(3));
        box.addView(t);
        box.addView(view);
    }

    private ScrollView scroll(View view) {
        ScrollView s = new ScrollView(this);
        s.addView(view);
        return s;
    }

    private void dialog(String title, View content,
                        String command, Runnable action) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll(content))
                .setNegativeButton("取消", null);
        if (command != null) {
            builder.setPositiveButton(command, (d, w) -> action.run());
        }
        builder.show();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        load();
        showShelf();
    }

    private File stateFile() {
        return new File(getFilesDir(), "library.json");
    }

    private File imageDir() {
        File dir = new File(getFilesDir(), "images");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
            if (out.size() + n > limit) throw new IOException("文件过大");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    private void load() {
        try {
            if (stateFile().exists()) {
                try (InputStream in = new FileInputStream(stateFile())) {
                    db = new JSONObject(new String(
                            readAll(in, 32 * 1024 * 1024),
                            StandardCharsets.UTF_8));
                }
            } else {
                db = obj();
            }
        } catch (Exception e) {
            db = obj();
            toast("记录读取失败。原文件未删除，请先备份：" + e.getMessage());
            // Keep the unreadable database instead of overwriting it.
            File backup = new File(getFilesDir(),
                    "library-unreadable-" + System.currentTimeMillis() + ".json");
            stateFile().renameTo(backup);
        }
        array(db, "items");
        array(db, "providers");
        if (!db.has("template")) {
            put(db, "template",
                    "识别英语试卷图片，保留题号。逐题给出答案、解题依据、"
                    + "相关语法和词汇。无法辨认的内容明确标注，不猜测。");
        }
    }

    private boolean save() {
        File temp = new File(getFilesDir(), "library.tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(db.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            android.system.Os.rename(
                    temp.getAbsolutePath(), stateFile().getAbsolutePath());
            return true;
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
            return false;
        }
    }

    private JSONObject item(String itemId) {
        JSONArray a = array(db, "items");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && itemId.equals(o.optString("id"))) return o;
        }
        return null;
    }

    private JSONObject book() {
        return item(bookId);
    }

    private JSONObject node(JSONObject b, String nodeId) {
        JSONArray a = array(b, "nodes");
        for (int i = 0; i < a.length(); i++) {
            JSONObject n = a.optJSONObject(i);
            if (n != null && nodeId.equals(n.optString("id"))) return n;
        }
        return null;
    }

    private ArrayList<JSONObject> branch(JSONObject b, String tip) {
        ArrayList<JSONObject> list = new ArrayList<>();
        HashSet<String> visited = new HashSet<>();
        while (!tip.isEmpty() && visited.add(tip)) {
            JSONObject n = node(b, tip);
            if (n == null) break;
            list.add(n);
            tip = n.optString("parent");
        }
        Collections.reverse(list);
        return list;
    }

    private void shell(String title) {
        root = column();
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(true);

        toolbar = row();
        toolbar.setPadding(dp(8), dp(5), dp(8), dp(5));
        TextView heading = text(title, 19);
        toolbar.addView(heading,
                new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(toolbar);

        body = column();
        root.addView(body,
                new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
    }

    private String folderTitle() {
        JSONObject f = item(folder);
        return f == null ? "试卷书库" : f.optString("title");
    }

    private void showShelf() {
        bookId = "";
        selected = "";
        shell(folderTitle());
        toolbar.addView(button("↑", () -> {
            JSONObject f = item(folder);
            folder = f == null ? "" : f.optString("parent");
            showShelf();
        }));
        toolbar.addView(button("设置", this::settings));

        LinearLayout tools = row();
        tools.addView(button("新建试卷", this::newBook),
                new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(button("新建文件夹", this::newFolder),
                new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(tools);

        LinearLayout edit = row();
        edit.addView(button("编辑", this::editSelected));
        edit.addView(button("移动", this::moveSelected));
        edit.addView(button("删除", this::deleteSelected));
        body.addView(edit);

        shelf = column();
        shelf.setPadding(dp(12), dp(8), dp(12), dp(12));
        body.addView(scroll(shelf),
                new LinearLayout.LayoutParams(-1, 0, 1));

        body.setOnDragListener((v, event) -> {
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return true;
            if (event.getAction() == DragEvent.ACTION_DROP) {
                String source = dragId(event);
                move(source, folder);
                showShelf();
            }
            return true;
        });
        renderShelf();
    }

    private void renderShelf() {
        shelf.removeAllViews();
        JSONArray a = array(db, "items");
        boolean found = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !folder.equals(o.optString("parent"))) continue;
            found = true;
            String itemId = o.optString("id");
            boolean isFolder = o.optBoolean("folder");
            LinearLayout card = row();
            card.setPadding(dp(12), dp(14), dp(12), dp(14));
            card.setBackgroundColor(itemId.equals(selected)
                    ? Color.rgb(218, 240, 235) : Color.rgb(243, 246, 247));
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(10);

            String cover = o.optString("cover");
            if (!isFolder && !cover.isEmpty()) {
                ImageView image = new ImageView(this);
                Bitmap bitmap = thumbnail(cover);
                if (bitmap != null) image.setImageBitmap(bitmap);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(image,
                        new LinearLayout.LayoutParams(dp(60), dp(78)));
            } else {
                TextView icon = text(isFolder ? "▣" : "▤", 32);
                icon.setGravity(Gravity.CENTER);
                card.addView(icon,
                        new LinearLayout.LayoutParams(dp(60), dp(78)));
            }

            LinearLayout caption = column();
            caption.setPadding(dp(12), 0, 0, 0);
            caption.addView(text(o.optString("title"), 17));
            caption.addView(text(o.optString("note"), 13));
            card.addView(caption,
                    new LinearLayout.LayoutParams(0, -2, 1));

            card.setOnClickListener(v -> {
                long now = SystemClock.uptimeMillis();
                if (itemId.equals(lastTapId) && now - lastTap < 350) {
                    lastTapId = "";
                    if (isFolder) {
                        folder = itemId;
                        showShelf();
                    } else openBook(itemId);
                } else {
                    lastTapId = itemId;
                    lastTap = now;
                    selected = itemId;
                    renderShelf();
                }
            });
            card.setOnLongClickListener(v -> {
                selected = itemId;
                ClipData data = ClipData.newPlainText("item", itemId);
                v.startDragAndDrop(data,
                        new View.DragShadowBuilder(v), null, 0);
                return true;
            });
            card.setOnDragListener((v, event) -> {
                if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) {
                    return true;
                }
                if (event.getAction() == DragEvent.ACTION_DROP) {
                    String source = dragId(event);
                    if (source.equals(itemId)) return true;
                    if (isFolder) move(source, itemId);
                    else reorder(source, itemId);
                    showShelf();
                }
                return true;
            });
            shelf.addView(card, lp);
        }
        if (!found) shelf.addView(text("暂无试卷", 16));
    }

    private String dragId(DragEvent event) {
        ClipData data = event.getClipData();
        return data == null || data.getItemCount() == 0 ? ""
                : data.getItemAt(0).coerceToText(this).toString();
    }

    private void reorder(String source, String target) {
        JSONObject s = item(source), t = item(target);
        if (s == null || t == null ||
                !s.optString("parent").equals(t.optString("parent"))) return;
        JSONArray old = array(db, "items"), next = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (source.equals(o.optString("id"))) continue;
            if (target.equals(o.optString("id"))) next.put(s);
            next.put(o);
        }
        put(db, "items", next);
        save();
    }

    private void move(String source, String destination) {
        JSONObject s = item(source);
        if (s == null) return;
        HashSet<String> visited = new HashSet<>();
        String p = destination;
        while (!p.isEmpty()) {
            if (p.equals(source) || !visited.add(p)) {
                toast("不能移入自身或子文件夹");
                return;
            }
            JSONObject f = item(p);
            if (f == null || !f.optBoolean("folder")) return;
            p = f.optString("parent");
        }
        put(s, "parent", destination);
        save();
    }

    private void newFolder() {
        EditText name = field("文件夹名称", "");
        dialog("新建文件夹", name, "创建", () -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) { toast("名称不能为空"); return; }
            JSONObject f = put(obj(), "id", id());
            put(f, "title", value);
            put(f, "parent", folder);
            put(f, "folder", true);
            array(db, "items").put(f);
            save();
            showShelf();
        });
    }

    private void newBook() {
        EditText name = field("试卷名称", "");
        dialog("新建试卷", name, "创建", () -> {
            String value = name.getText().toString().trim();
            JSONObject b = put(obj(), "id", id());
            put(b, "title", value.isEmpty() ? "新试卷" : value);
            put(b, "parent", folder);
            put(b, "note", "");
            put(b, "folder", false);
            put(b, "nodes", new JSONArray());
            put(b, "tip", "");
            put(b, "template", db.optString("template"));
            array(db, "items").put(b);
            save();
            openBook(b.optString("id"));
        });
    }

    private void editSelected() {
        JSONObject o = item(selected);
        if (o == null) { toast("先单击选中书或文件夹"); return; }
        LinearLayout box = column();
        box.setPadding(dp(16), 0, dp(16), dp(12));
        EditText name = field("名称", o.optString("title"));
        EditText note = field("封面备注", o.optString("note"));
        labelled(box, "名称", name);
        labelled(box, "备注", note);
        dialog("编辑", box, "保存", () -> {
            if (name.getText().toString().trim().isEmpty()) {
                toast("名称不能为空"); return;
            }
            put(o, "title", name.getText().toString().trim());
            put(o, "note", note.getText().toString());
            save();
            renderShelf();
        });
    }

    private void moveSelected() {
        if (item(selected) == null) { toast("先单击选中"); return; }
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        ids.add(""); names.add("书库根目录");
        JSONArray a = array(db, "items");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o.optBoolean("folder")) {
                ids.add(o.optString("id"));
                names.add(o.optString("title"));
            }
        }
        new AlertDialog.Builder(this).setTitle("移动到")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    move(selected, ids.get(w));
                    showShelf();
                }).show();
    }

    private void deleteSelected() {
        JSONObject o = item(selected);
        if (o == null) { toast("先单击选中"); return; }
        new AlertDialog.Builder(this)
                .setTitle("删除 " + o.optString("title") + "？")
                .setMessage("文件夹内的全部试卷也会删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    HashSet<String> removed = new HashSet<>();
                    removed.add(selected);
                    JSONArray a = array(db, "items");
                    boolean changed;
                    do {
                        changed = false;
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject x = a.optJSONObject(i);
                            if (removed.contains(x.optString("parent")) &&
                                    removed.add(x.optString("id"))) changed = true;
                        }
                    } while (changed);
                    JSONArray next = new JSONArray();
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject x = a.optJSONObject(i);
                        if (!removed.contains(x.optString("id"))) next.put(x);
                    }
                    put(db, "items", next);
                    if (save()) cleanupImages();
                    showShelf();
                }).show();
    }

    private void openBook(String value) {
        bookId = value;
        pending.clear();
        expanded.clear();
        flowMode = false;
        showChat();
    }

    private void showChat() {
        JSONObject b = book();
        if (b == null) { showShelf(); return; }
        shell(b.optString("title"));
        toolbar.addView(button("书库", () -> {
            if (busy) { toast("先停止生成"); return; }
            folder = b.optString("parent");
            pending.clear();
            showShelf();
        }));
        toolbar.addView(button(flowMode ? "聊天" : "流程", () -> {
            if (busy) { toast("生成结束后再切换"); return; }
            flowMode = !flowMode;
            showChat();
        }));

        LinearLayout tools = row();
        tools.addView(button("模型", this::chooseProvider));
        tools.addView(button("模板", this::bookTemplate));
        tools.addView(button("重生成", this::regenerate));
        tools.addView(button("设置", this::settings));
        body.addView(tools);

        if (flowMode) {
            body.addView(new FlowView(),
                    new LinearLayout.LayoutParams(-1, 0, 1));
            return;
        }

        chatScroll = new ScrollView(this);
        messages = column();
        messages.setPadding(dp(14), dp(8), dp(14), dp(10));
        chatScroll.addView(messages);
        body.addView(chatScroll,
                new LinearLayout.LayoutParams(-1, 0, 1));

        attachments = row();
        body.addView(attachments);

        LinearLayout composer = row();
        composer.setPadding(dp(6), 0, dp(6), 0);
        composer.addView(button("＋", () -> {
            if (busy) { toast("正在生成"); return; }
            if (pending.size() >= 4) {
                toast("每条消息最多四张图片"); return;
            }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, PICK_IMAGE);
        }));
        input = field("输入补充要求或追问", "");
        input.setMaxLines(4);
        composer.addView(input,
                new LinearLayout.LayoutParams(0, -2, 1));
        sendButton = button(busy ? "停止" : "发送", () -> {
            if (busy) stopRequest();
            else send();
        });
        composer.addView(sendButton);
        body.addView(composer);
        status = text("", 12);
        status.setPadding(dp(12), dp(4), dp(12), dp(8));
        body.addView(status);
        renderChat();
        renderAttachments();
    }

    private void renderAttachments() {
        if (attachments == null) return;
        attachments.removeAllViews();
        for (String path : pending) {
            Button b = button("图片 ×", () -> {
                if (busy) return;
                pending.remove(path);
                renderAttachments();
            });
            attachments.addView(b);
        }
    }

    private void renderChat() {
        if (messages == null || flowMode || book() == null) return;
        messages.removeAllViews();
        JSONObject b = book();
        for (JSONObject n : branch(b, b.optString("tip"))) {
            TextView role = text(
                    n.optString("role").equals("user") ? "你" : "AI", 12);
            role.setPadding(0, dp(12), 0, dp(6));
            messages.addView(role);
            JSONArray images = n.optJSONArray("images");
            if (images != null) {
                for (int i = 0; i < images.length(); i++) {
                    String path = images.optString(i);
                    ImageView image = new ImageView(this);
                    Bitmap bitmap = thumbnail(path);
                    if (bitmap != null) image.setImageBitmap(bitmap);
                    image.setAdjustViewBounds(true);
                    image.setMaxHeight(dp(230));
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    messages.addView(image,
                            new LinearLayout.LayoutParams(-1, -2));
                }
            }
            String reason = n.optString("reasoning");
            if (!reason.isEmpty()) {
                String nodeId = n.optString("id");
                TextView reasoning = text(reason, 14);
                reasoning.setPadding(dp(10), dp(6), dp(10), dp(8));
                reasoning.setTextColor(Color.rgb(95, 111, 119));
                Button toggle = button("思考", () -> {});
                Runnable apply = () -> {
                    boolean visible = expanded.contains(nodeId);
                    toggle.setText(visible ? "收起思考" : "展开思考");
                    reasoning.setVisibility(visible ? View.VISIBLE : View.GONE);
                };
                toggle.setOnClickListener(v -> {
                    if (!expanded.add(nodeId)) expanded.remove(nodeId);
                    apply.run();
                });
                apply.run();
                messages.addView(toggle);
                messages.addView(reasoning);
            }
            TextView answer = text(n.optString("text"), 16);
            answer.setTextIsSelectable(true);
            answer.setLineSpacing(dp(4), 1);
            messages.addView(answer);
            if (!n.optString("error").isEmpty()) {
                TextView error = text(n.optString("error"), 13);
                error.setTextColor(Color.rgb(180, 40, 50));
                messages.addView(error);
            }
            LinearLayout actions = row();
            actions.addView(button("复制", () -> {
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(
                        ClipData.newPlainText("message", n.optString("text")));
                toast("已复制");
            }));
            if (n.optString("role").equals("user")) {
                actions.addView(button("编辑分支", () -> {
                    if (busy) return;
                    put(b, "tip", n.optString("parent"));
                    input.setText(n.optString("text"));
                    pending.clear();
                    JSONArray imgs = n.optJSONArray("images");
                    if (imgs != null) {
                        for (int i = 0; i < imgs.length(); i++) {
                            pending.add(imgs.optString(i));
                        }
                    }
                    save();
                    renderChat();
                    renderAttachments();
                }));
            }
            messages.addView(actions);
        }
    }

    private void bookTemplate() {
        if (busy) return;
        JSONObject b = book();
        EditText e = field("本试卷模板", b.optString("template"));
        e.setMinLines(5);
        dialog("本试卷模板", e, "保存", () -> {
            put(b, "template", e.getText().toString());
            save();
        });
    }

    private JSONObject provider() {
        String chosen = book() == null ? "" : book().optString("provider");
        if (chosen.isEmpty()) chosen = db.optString("provider");
        JSONArray a = array(db, "providers");
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (chosen.equals(p.optString("id"))) return p;
        }
        return a.optJSONObject(0);
    }

    private void chooseProvider() {
        if (busy) return;
        JSONArray providers = array(db, "providers");
        if (providers.length() == 0) {
            settings(); return;
        }
        String[] names = new String[providers.length()];
        for (int i = 0; i < names.length; i++) {
            JSONObject p = providers.optJSONObject(i);
            names[i] = p.optString("name") + " · " + p.optString("model");
        }
        new AlertDialog.Builder(this).setTitle("模型配置")
                .setItems(names, (d, w) -> {
                    put(book(), "provider",
                            providers.optJSONObject(w).optString("id"));
                    save();
                }).show();
    }

    private SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias("exam-api-key")) {
            return (SecretKey) store.getKey("exam-api-key", null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                "AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder("exam-api-key",
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(
                plain.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private String decrypt(String encoded) throws Exception {
        if (encoded.isEmpty()) return "";
        String[] parts = encoded.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                new GCMParameterSpec(128,
                        Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(
                Base64.decode(parts[1], Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
    }

    private void settings() {
        if (busy) { toast("先停止生成"); return; }
        LinearLayout box = column();
        box.setPadding(dp(16), 0, dp(16), dp(16));
        JSONArray providers = array(db, "providers");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            box.addView(button(
                    p.optString("name") + " · " + p.optString("model"),
                    () -> editProvider(p)));
        }
        box.addView(button("添加模型配置", () -> editProvider(null)));
        EditText template = field("默认模板", db.optString("template"));
        template.setMinLines(4);
        labelled(box, "新试卷默认模板", template);
        box.addView(button("导出备份", () -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.setType("application/json");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_TITLE, "exam-library.json");
            startActivityForResult(i, EXPORT);
        }));
        box.addView(button("导入备份", () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, IMPORT);
        }));
        dialog("设置", box, "保存模板", () -> {
            put(db, "template", template.getText().toString());
            save();
        });
    }

    private void editProvider(JSONObject existing) {
        JSONObject p = existing == null ? obj() : existing;
        LinearLayout box = column();
        box.setPadding(dp(16), 0, dp(16), dp(14));
        EditText name = field("配置名称", p.optString("name"));
        EditText base = field("https://服务商/v1", p.optString("base"));
        EditText key = field("API 密钥", "");
        key.setInputType(129);
        try { key.setText(decrypt(p.optString("key"))); }
        catch (Exception e) { toast("密钥无法解密，请重新填写"); }
        EditText model = field("模型 ID", p.optString("model"));
        EditText temperature = field("温度，例如 0.3",
                p.optString("temperature", "0.3"));
        temperature.setInputType(8194);
        labelled(box, "名称", name);
        labelled(box, "API 地址", base);
        labelled(box, "密钥", key);
        labelled(box, "模型", model);
        labelled(box, "温度；不支持此参数的模型可留空", temperature);
        CheckBox vision = new CheckBox(this);
        vision.setText("允许图片输入");
        vision.setChecked(p.optBoolean("vision", true));
        box.addView(vision);
        if (existing != null) {
            box.addView(button("删除此配置", () -> {
                JSONArray next = new JSONArray();
                JSONArray old = array(db, "providers");
                for (int j = 0; j < old.length(); j++) {
                    JSONObject x = old.optJSONObject(j);
                    if (!x.optString("id").equals(p.optString("id"))) next.put(x);
                }
                put(db, "providers", next);
                save();
                toast("已删除；关闭此窗口即可");
            }));
        }
        dialog("模型提供方", box, "保存", () -> {
            try {
                String url = base.getText().toString().trim();
                endpoint(url);
                if (model.getText().toString().trim().isEmpty() ||
                        key.getText().toString().trim().isEmpty()) {
                    throw new Exception("密钥和模型不能为空");
                }
                String temp = temperature.getText().toString().trim();
                if (!temp.isEmpty()) {
                    double value = Double.parseDouble(temp);
                    if (!Double.isFinite(value) || value < 0 || value > 2) {
                        throw new Exception("温度须在 0 到 2 之间");
                    }
                }
                if (existing == null) put(p, "id", id());
                put(p, "name", name.getText().toString().trim());
                put(p, "base", url);
                put(p, "key", encrypt(key.getText().toString().trim()));
                put(p, "model", model.getText().toString().trim());
                put(p, "temperature", temp);
                put(p, "vision", vision.isChecked());
                if (existing == null) array(db, "providers").put(p);
                put(db, "provider", p.optString("id"));
                save();
                toast("配置已保存");
            } catch (Exception e) { toast(e.getMessage()); }
        });
    }

    private static URL endpoint(String base) throws Exception {
        String value = base.trim().replaceAll("/+$", "");
        if (!value.endsWith("/chat/completions")) {
            value += "/chat/completions";
        }
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol()) ||
                url.getHost().isEmpty() ||
                url.getQuery() != null || url.getRef() != null) {
            throw new Exception("请填写 HTTPS 接口地址，不包含查询参数");
        }
        return url;
    }

    private JSONObject newNode(String role, String parent, String value) {
        JSONObject n = put(obj(), "id", id());
        put(n, "role", role);
        put(n, "parent", parent);
        put(n, "text", value);
        put(n, "reasoning", "");
        return n;
    }

    private void send() {
        JSONObject p = provider();
        if (p == null) { settings(); return; }
        String value = input.getText().toString().trim();
        if (value.isEmpty() && pending.isEmpty()) return;
        if (!pending.isEmpty() && !p.optBoolean("vision", true)) {
            toast("此模型配置未启用图片"); return;
        }
        JSONObject b = book();
        JSONObject user = newNode("user", b.optString("tip"), value);
        put(user, "images", new JSONArray(pending));
        array(b, "nodes").put(user);
        put(b, "tip", user.optString("id"));
        if (b.optString("cover").isEmpty() && !pending.isEmpty()) {
            put(b, "cover", pending.get(0));
        }
        pending.clear();
        input.setText("");
        save();
        generate();
    }

    private void regenerate() {
        if (busy || book() == null) return;
        JSONObject b = book();
        JSONObject tip = node(b, b.optString("tip"));
        if (tip == null) return;
        if ("assistant".equals(tip.optString("role"))) {
            put(b, "tip", tip.optString("parent"));
        }
        if (provider() == null) { settings(); return; }
        generate();
    }

    private JSONArray requestMessages(JSONObject b, String tip)
            throws Exception {
        JSONArray result = new JSONArray();
        if (!b.optString("template").trim().isEmpty()) {
            result.put(put(put(obj(), "role", "system"),
                    "content", b.optString("template")));
        }
        for (JSONObject n : branch(b, tip)) {
            String role = n.optString("role");
            String value = n.optString("text");
            if ("assistant".equals(role) && value.isEmpty()) continue;
            JSONArray images = n.optJSONArray("images");
            JSONObject m = put(obj(), "role", role);
            if (images != null && images.length() > 0) {
                JSONArray content = new JSONArray();
                content.put(put(put(obj(), "type", "text"), "text",
                        value.isEmpty()
                                ? "请按固定模板分析这些图片。" : value));
                for (int i = 0; i < images.length(); i++) {
                    File f = safeImage(images.optString(i));
                    byte[] bytes;
                    try (InputStream in = new FileInputStream(f)) {
                        bytes = readAll(in, 8 * 1024 * 1024);
                    }
                    String data = "data:image/jpeg;base64,"
                            + Base64.encodeToString(bytes, Base64.NO_WRAP);
                    content.put(put(put(obj(), "type", "image_url"),
                            "image_url", put(obj(), "url", data)));
                }
                put(m, "content", content);
            } else put(m, "content", value);
            result.put(m);
        }
        return result;
    }

    private void stopRequest() {
        cancelled = true;
        HttpURLConnection c = connection;
        if (c != null) workerStop(c);
        if (status != null) status.setText("正在停止…");
    }

    private void workerStop(HttpURLConnection c) {
        new Thread(c::disconnect).start();
    }

    private void generate() {
        if (busy) return;
        JSONObject b = book(), p = provider();
        if (b == null || p == null) return;

        String tip = b.optString("tip");
        final URL url;
        final String key;
        final JSONObject request;
        try {
            url = endpoint(p.optString("base"));
            key = decrypt(p.optString("key"));
            request = put(obj(), "model", p.optString("model"));
            put(request, "stream", true);
            put(request, "messages", requestMessages(b, tip));
            String temperature = p.optString("temperature");
            if (!temperature.isEmpty()) {
                put(request, "temperature", Double.parseDouble(temperature));
            }
        } catch (Exception e) {
            toast("请求准备失败：" + e.getMessage()); return;
        }

        JSONObject answer = newNode("assistant", tip, "");
        array(b, "nodes").put(answer);
        put(b, "tip", answer.optString("id"));
        save();
        busy = true;
        cancelled = false;
        sendButton.setText("停止");
        input.setEnabled(false);
        renderAttachments();
        renderChat();
        status.setText("正在连接…");

        worker.execute(() -> {
            StringBuilder result = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            String error = "";
            long lastUpdate = 0;
            HttpURLConnection c = null;
            try {
                if (cancelled) throw new IOException("已停止");
                c = (HttpURLConnection) url.openConnection();
                connection = c;
                c.setRequestMethod("POST");
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(30000);
                c.setReadTimeout(90000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + key);
                byte[] payload = request.toString()
                        .getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(payload.length);
                if (cancelled) throw new IOException("已停止");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(payload);
                }
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    InputStream err = c.getErrorStream();
                    String detail = "";
                    if (err != null) {
                        try (InputStream in = err) {
                            detail = new String(readAll(in, 128 * 1024),
                                    StandardCharsets.UTF_8);
                        }
                    }
                    throw new IOException("HTTP " + code + " "
                            + detail.substring(0, Math.min(1600, detail.length())));
                }

                String type = c.getContentType();
                if (type != null && type.contains("text/event-stream")) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(c.getInputStream(),
                                    StandardCharsets.UTF_8))) {
                        String line;
                        StringBuilder event = new StringBuilder();
                        while (!cancelled && (line = reader.readLine()) != null) {
                            if (line.isEmpty()) {
                                if ("[DONE]".equals(event.toString())) break;
                                if (event.length() > 0) {
                                    parseEvent(event.toString(), result, reasoning);
                                    event.setLength(0);
                                }
                                long now = SystemClock.uptimeMillis();
                                if (now - lastUpdate > 180) {
                                    publish(answer, result.toString(),
                                            reasoning.toString(), false, "");
                                    lastUpdate = now;
                                }
                            } else if (line.startsWith("data:")) {
                                if (event.length() > 0) event.append('\n');
                                String data = line.substring(5);
                                if (data.startsWith(" ")) data = data.substring(1);
                                event.append(data);
                            }
                        }
                        if (event.length() > 0 &&
                                !"[DONE]".equals(event.toString())) {
                            parseEvent(event.toString(), result, reasoning);
                        }
                    }
                } else {
                    String response;
                    try (InputStream in = c.getInputStream()) {
                        response = new String(readAll(in, 8 * 1024 * 1024),
                                StandardCharsets.UTF_8);
                    }
                    if (response.trim().startsWith("<")) {
                        throw new IOException(
                                "接口返回了网页。请核对 API 地址，通常应包含 /v1。");
                    }
                    JSONObject data = new JSONObject(response);
                    if (data.has("error")) {
                        throw new IOException(data.opt("error").toString());
                    }
                    JSONObject message = data.getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message");
                    result.append(message.optString("content"));
                    reasoning.append(message.optString("reasoning_content"));
                }
                if (cancelled) error = "已停止";
                else if (result.length() == 0 && reasoning.length() == 0) {
                    error = "接口未返回文本；请检查模型和接口格式。";
                }
            } catch (Exception e) {
                error = cancelled ? "已停止" : "请求失败：" + e.getMessage();
            } finally {
                if (c != null) c.disconnect();
                connection = null;
            }
            publish(answer, result.toString(), reasoning.toString(), true, error);
        });
    }

    private void parseEvent(String event, StringBuilder result,
                            StringBuilder reasoning) throws Exception {
        JSONObject data = new JSONObject(event);
        if (data.has("error")) {
            throw new IOException(data.opt("error").toString());
        }
        JSONArray choices = data.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return;
        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
        if (delta == null) return;
        Object content = delta.opt("content");
        Object reason = delta.opt("reasoning_content");
        if (content instanceof String) result.append(content);
        if (reason instanceof String) reasoning.append(reason);
    }

    private void publish(JSONObject answer, String value,
                         String reason, boolean finished, String error) {
        ui.post(() -> {
            put(answer, "text", value);
            put(answer, "reasoning", reason);
            if (finished) {
                put(answer, "error", error);
                busy = false;
                if (sendButton != null) sendButton.setText("发送");
                if (input != null) input.setEnabled(true);
                save();
            }
            if (status != null) {
                status.setText(finished
                        ? (error.isEmpty() ? "已完成" : error) : "正在生成…");
            }
            renderChat();
        });
    }

    private File safeImage(String filename) throws IOException {
        if (!filename.matches("[a-zA-Z0-9-]+\\.jpg")) {
            throw new IOException("图片文件名无效");
        }
        return new File(imageDir(), filename);
    }

    private Bitmap thumbnail(String path) {
        try {
            File f = safeImage(path);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), options);
            options.inSampleSize = Math.max(1,
                    Math.max(options.outWidth, options.outHeight) / 600);
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(f.getPath(), options);
        } catch (Exception e) { return null; }
    }

    private Bitmap decodeImage(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("图片格式无法读取");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight)
                / options.inSampleSize > 2000) options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) throw new IOException("无法解码图片");
        return bitmap;
    }

    private void imageEditor(Bitmap original) {
        ImageEditor editor = new ImageEditor(original);
        LinearLayout box = column();
        box.addView(editor,
                new LinearLayout.LayoutParams(-1, dp(320)));
        LinearLayout tools = row();
        tools.addView(button("↻", () -> editor.rotate()));
        tools.addView(button("裁切", () -> editor.mode(false)));
        tools.addView(button("透视", () -> editor.mode(true)));
        tools.addView(button("应用", () -> editor.apply()));
        box.addView(tools);
        labelled(box, "宽度比例", editor.slider(true));
        labelled(box, "高度比例", editor.slider(false));
        box.addView(button("恢复原图", editor::reset));

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("图片处理")
                .setView(scroll(box))
                .setNegativeButton("取消", null)
                .setPositiveButton("加入对话", null)
                .create();
        d.setOnShowListener(v -> d.getButton(-1).setOnClickListener(view -> {
            try {
                Bitmap output = editor.output();
                String name = id() + ".jpg";
                File file = safeImage(name);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    if (!output.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
                        throw new IOException("图片保存失败");
                    }
                }
                pending.add(name);
                renderAttachments();
                d.dismiss();
            } catch (Exception e) { toast(e.getMessage()); }
        }));
        d.show();
    }

    private class ImageEditor extends View {
        final Bitmap original;
        Bitmap bitmap;
        final Paint paint = new Paint(3);
        final PointF[] corners = new PointF[4];
        RectF area = new RectF();
        float displayScale = 1;
        float widthRatio = 1, heightRatio = 1;
        boolean perspective = true;
        int active = -1;
        SeekBar widthBar, heightBar;

        ImageEditor(Bitmap value) {
            super(MainActivity.this);
            original = value;
            bitmap = value;
            resetCorners();
        }

        void resetCorners() {
            corners[0] = new PointF(0, 0);
            corners[1] = new PointF(bitmap.getWidth(), 0);
            corners[2] = new PointF(bitmap.getWidth(), bitmap.getHeight());
            corners[3] = new PointF(0, bitmap.getHeight());
            invalidate();
        }

        void rotate() {
            Matrix m = new Matrix();
            m.postRotate(90);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), m, true);
            resetCorners();
        }

        void mode(boolean value) {
            perspective = value;
            resetCorners();
        }

        void reset() {
            bitmap = original;
            widthRatio = heightRatio = 1;
            if (widthBar != null) widthBar.setProgress(100);
            if (heightBar != null) heightBar.setProgress(100);
            resetCorners();
        }

        SeekBar slider(boolean width) {
            SeekBar s = new SeekBar(MainActivity.this);
            s.setMax(200);
            s.setProgress(100);
            if (width) widthBar = s;
            else heightBar = s;
            s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int p, boolean user) {
                    float ratio = 0.5f + p / 200f;
                    if (width) widthRatio = ratio;
                    else heightRatio = ratio;
                }
                public void onStartTrackingTouch(SeekBar bar) {}
                public void onStopTrackingTouch(SeekBar bar) {}
            });
            return s;
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(237, 243, 244));
            float pad = dp(20);
            displayScale = Math.min((getWidth() - 2 * pad) / bitmap.getWidth(),
                    (getHeight() - 2 * pad) / bitmap.getHeight());
            if (displayScale <= 0) return;
            float bw = bitmap.getWidth() * displayScale;
            float bh = bitmap.getHeight() * displayScale;
            area.set((getWidth() - bw) / 2, (getHeight() - bh) / 2,
                    (getWidth() + bw) / 2, (getHeight() + bh) / 2);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawBitmap(bitmap, null, area, paint);
            Path path = new Path();
            for (int i = 0; i < 4; i++) {
                float x = area.left + corners[i].x * displayScale;
                float y = area.top + corners[i].y * displayScale;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            paint.setColor(Color.rgb(12, 143, 119));
            paint.setStrokeWidth(dp(2));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            for (PointF p : corners) {
                canvas.drawCircle(area.left + p.x * displayScale,
                        area.top + p.y * displayScale, dp(9), paint);
            }
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (displayScale <= 0) return false;
            float x = Math.max(0, Math.min(bitmap.getWidth(),
                    (e.getX() - area.left) / displayScale));
            float y = Math.max(0, Math.min(bitmap.getHeight(),
                    (e.getY() - area.top) / displayScale));
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                active = -1;
                double distance = dp(40) / displayScale;
                for (int i = 0; i < 4; i++) {
                    double d = Math.hypot(corners[i].x - x, corners[i].y - y);
                    if (d < distance) { distance = d; active = i; }
                }
                if (active < 0) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
            } else if (e.getAction() == MotionEvent.ACTION_MOVE && active >= 0) {
                corners[active].set(x, y);
                if (!perspective) {
                    int opposite = (active + 2) % 4;
                    PointF anchor = corners[opposite];
                    float left = Math.min(x, anchor.x);
                    float right = Math.max(x, anchor.x);
                    float top = Math.min(y, anchor.y);
                    float bottom = Math.max(y, anchor.y);
                    corners[0].set(left, top);
                    corners[1].set(right, top);
                    corners[2].set(right, bottom);
                    corners[3].set(left, bottom);
                    active = (x <= anchor.x ? 0 : 1)
                            + (y <= anchor.y ? 0 : 2);
                    if (active == 2) active = 3;
                    else if (active == 3) active = 2;
                }
                invalidate();
            } else if (e.getAction() == MotionEvent.ACTION_UP ||
                    e.getAction() == MotionEvent.ACTION_CANCEL) {
                active = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return true;
        }

        float length(PointF a, PointF b) {
            return (float) Math.hypot(a.x - b.x, a.y - b.y);
        }

        Bitmap corrected() throws Exception {
            // Reject crossed or near-degenerate quadrilaterals.
            float sign = 0;
            for (int i = 0; i < 4; i++) {
                PointF a = corners[i], b = corners[(i + 1) % 4],
                        c = corners[(i + 2) % 4];
                float cross = (b.x - a.x) * (c.y - b.y)
                        - (b.y - a.y) * (c.x - b.x);
                if (Math.abs(cross) < 10 || (i > 0 && cross * sign <= 0)) {
                    throw new Exception("四角不能交叉或重叠");
                }
                sign = cross;
            }
            int width = Math.round(Math.max(length(corners[0], corners[1]),
                    length(corners[3], corners[2])));
            int height = Math.round(Math.max(length(corners[0], corners[3]),
                    length(corners[1], corners[2])));
            if (width < 20 || height < 20) throw new Exception("裁切区域过小");
            width = Math.min(width, 2200);
            height = Math.min(height, 2200);
            float[] src = new float[8];
            for (int i = 0; i < 4; i++) {
                src[i * 2] = corners[i].x;
                src[i * 2 + 1] = corners[i].y;
            }
            float[] dst = {0, 0, width, 0, width, height, 0, height};
            Matrix matrix = new Matrix();
            if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
                throw new Exception("无法校正该区域");
            }
            Bitmap output = Bitmap.createBitmap(width, height,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(bitmap, matrix, new Paint(3));
            return output;
        }

        void apply() {
            try { bitmap = corrected(); resetCorners(); }
            catch (Exception e) { toast(e.getMessage()); }
        }

        Bitmap output() throws Exception {
            Bitmap b = corrected();
            int width = Math.max(20, Math.round(b.getWidth() * widthRatio));
            int height = Math.max(20, Math.round(b.getHeight() * heightRatio));
            float scale = Math.min(1, 2400f / Math.max(width, height));
            return Bitmap.createScaledBitmap(b,
                    Math.round(width * scale), Math.round(height * scale), true);
        }
    }

    private class FlowView extends View {
        final Paint paint = new Paint(3);
        final HashMap<String, RectF> boxes = new HashMap<>();
        float scale = 1, offsetX = dp(20), offsetY = dp(20);
        float downX, downY, startX, startY;
        boolean moved = false, pinched = false;
        final ScaleGestureDetector zoom;

        FlowView() {
            super(MainActivity.this);
            zoom = new ScaleGestureDetector(MainActivity.this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScale(ScaleGestureDetector d) {
                            float old = scale;
                            scale = Math.max(.25f,
                                    Math.min(2.5f, scale * d.getScaleFactor()));
                            offsetX = d.getFocusX()
                                    - (d.getFocusX() - offsetX) * scale / old;
                            offsetY = d.getFocusY()
                                    - (d.getFocusY() - offsetY) * scale / old;
                            pinched = true;
                            invalidate();
                            return true;
                        }
                    });
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(245, 248, 249));
            JSONObject b = book();
            if (b == null) return;
            JSONArray nodes = array(b, "nodes");
            boxes.clear();
            HashMap<Integer, Integer> rows = new HashMap<>();
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.optJSONObject(i);
                int depth = branch(b, n.optString("id")).size() - 1;
                int r = rows.containsKey(depth) ? rows.get(depth) : 0;
                rows.put(depth, r + 1);
                float x = depth * dp(205);
                float y = r * dp(105);
                boxes.put(n.optString("id"),
                        new RectF(x, y, x + dp(180), y + dp(78)));
            }
            canvas.save();
            canvas.translate(offsetX, offsetY);
            canvas.scale(scale, scale);
            paint.setColor(Color.rgb(146, 172, 177));
            paint.setStrokeWidth(dp(2));
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.optJSONObject(i);
                RectF to = boxes.get(n.optString("id"));
                RectF from = boxes.get(n.optString("parent"));
                if (from != null && to != null) {
                    canvas.drawLine(from.right, from.centerY(),
                            to.left, to.centerY(), paint);
                    float x = to.left, y = to.centerY();
                    canvas.drawLine(x, y, x - dp(7), y - dp(5), paint);
                    canvas.drawLine(x, y, x - dp(7), y + dp(5), paint);
                }
            }
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.optJSONObject(i);
                RectF rect = boxes.get(n.optString("id"));
                paint.setColor(n.optString("id").equals(b.optString("tip"))
                        ? Color.rgb(205, 238, 227) : Color.WHITE);
                canvas.drawRoundRect(rect, dp(6), dp(6), paint);
                paint.setColor(Color.rgb(39, 65, 71));
                paint.setTextSize(dp(13));
                canvas.drawText("user".equals(n.optString("role"))
                        ? "提问" : "AI 回答", rect.left + dp(10),
                        rect.top + dp(22), paint);
                String value = n.optString("text").replace('\n', ' ');
                if (value.isEmpty()) value = "图片 / 未返回文本";
                int count = paint.breakText(value, true, rect.width() - dp(20), null);
                canvas.drawText(value.substring(0, count),
                        rect.left + dp(10), rect.top + dp(48), paint);
            }
            canvas.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            zoom.onTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getX(); downY = e.getY();
                startX = offsetX; startY = offsetY;
                moved = false; pinched = false;
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE &&
                    !zoom.isInProgress() && e.getPointerCount() == 1 && !pinched) {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.hypot(dx, dy) > dp(8)) moved = true;
                offsetX = startX + dx; offsetY = startY + dy;
                invalidate();
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP &&
                    !moved && !pinched) {
                float x = (e.getX() - offsetX) / scale;
                float y = (e.getY() - offsetY) / scale;
                for (Map.Entry<String, RectF> entry : boxes.entrySet()) {
                    if (entry.getValue().contains(x, y)) {
                        put(book(), "tip", entry.getKey());
                        save();
                        flowMode = false;
                        showChat();
                        break;
                    }
                }
            }
            return true;
        }
    }

    private void cleanupImages() {
        HashSet<String> used = new HashSet<>(pending);
        JSONArray items = array(db, "items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject b = items.optJSONObject(i);
            if (!b.optString("cover").isEmpty()) used.add(b.optString("cover"));
            JSONArray nodes = b.optJSONArray("nodes");
            if (nodes == null) continue;
            for (int j = 0; j < nodes.length(); j++) {
                JSONArray imgs = nodes.optJSONObject(j).optJSONArray("images");
                if (imgs == null) continue;
                for (int k = 0; k < imgs.length(); k++) used.add(imgs.optString(k));
            }
        }
        File[] files = imageDir().listFiles();
        if (files != null) for (File f : files) {
            if (!used.contains(f.getName())) f.delete();
        }
    }

    private JSONObject backup() throws Exception {
        JSONObject result = new JSONObject(db.toString());
        JSONArray providers = array(result, "providers");
        for (int i = 0; i < providers.length(); i++) {
            providers.getJSONObject(i).remove("key");
        }
        JSONObject images = obj();
        JSONArray items = array(result, "items");
        HashSet<String> paths = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject b = items.getJSONObject(i);
            if (!b.optString("cover").isEmpty()) paths.add(b.optString("cover"));
            JSONArray nodes = b.optJSONArray("nodes");
            if (nodes == null) continue;
            for (int j = 0; j < nodes.length(); j++) {
                JSONArray imgs = nodes.getJSONObject(j).optJSONArray("images");
                if (imgs != null) for (int k = 0; k < imgs.length(); k++) {
                    paths.add(imgs.optString(k));
                }
            }
        }
        long total = 0;
        for (String path : paths) {
            byte[] bytes;
            try (InputStream in = new FileInputStream(safeImage(path))) {
                bytes = readAll(in, 8 * 1024 * 1024);
            }
            total += bytes.length;
            if (total > 35 * 1024 * 1024) {
                throw new IOException("图片备份超过 35 MB，请减少试卷后重试");
            }
            put(images, path, Base64.encodeToString(bytes, Base64.NO_WRAP));
        }
        put(result, "imageData", images);
        put(result, "format", "exambook-1");
        return result;
    }

    private void importBackup(JSONObject data) throws Exception {
        if (!"exambook-1".equals(data.optString("format"))) {
            throw new IOException("不是本应用的备份");
        }
        JSONArray items = data.getJSONArray("items");
        JSONObject imageData = data.getJSONObject("imageData");
        HashMap<String, String> imageNames = new HashMap<>();
        Iterator<String> keys = imageData.keys();
        long total = 0;
        while (keys.hasNext()) {
            String old = keys.next();
            safeImage(old);
            byte[] bytes = Base64.decode(imageData.getString(old), Base64.NO_WRAP);
            total += bytes.length;
            if (bytes.length > 8 * 1024 * 1024 ||
                    total > 35 * 1024 * 1024) throw new IOException("图片过大");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 ||
                    options.outWidth > 10000 || options.outHeight > 10000) {
                throw new IOException("备份含无效图片");
            }
            String name = id() + ".jpg";
            try (OutputStream out = new FileOutputStream(safeImage(name))) {
                out.write(bytes);
            }
            imageNames.put(old, name);
        }

        HashMap<String, String> itemIds = new HashMap<>();
        HashMap<String, JSONObject> originals = new HashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject b = items.getJSONObject(i);
            String old = b.getString("id");
            if (old.isEmpty() || itemIds.containsKey(old)) {
                throw new IOException("试卷 ID 无效");
            }
            itemIds.put(old, id());
            originals.put(old, b);
        }
        JSONArray imported = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject b = items.getJSONObject(i);
            String old = b.getString("id");
            String parent = b.optString("parent");
            HashSet<String> seen = new HashSet<>();
            String ancestor = parent;
            while (!ancestor.isEmpty()) {
                JSONObject f = originals.get(ancestor);
                if (ancestor.equals(old) || !seen.add(ancestor) ||
                        f == null || !f.optBoolean("folder")) {
                    throw new IOException("文件夹结构无效");
                }
                ancestor = f.optString("parent");
            }
            put(b, "id", itemIds.get(old));
            put(b, "parent", parent.isEmpty() ? "" : itemIds.get(parent));
            b.remove("provider");
            String cover = b.optString("cover");
            put(b, "cover", imageNames.getOrDefault(cover, ""));

            JSONArray nodes = b.optJSONArray("nodes");
            if (nodes != null) {
                HashSet<String> nodeIds = new HashSet<>();
                for (int j = 0; j < nodes.length(); j++) {
                    JSONObject n = nodes.getJSONObject(j);
                    String nid = n.getString("id");
                    String role = n.getString("role");
                    if (nid.isEmpty() || !nodeIds.add(nid) ||
                            (!role.equals("user") && !role.equals("assistant"))) {
                        throw new IOException("对话节点无效");
                    }
                    String np = n.optString("parent");
                    if (!np.isEmpty() && !nodeIds.contains(np)) {
                        throw new IOException("对话分支顺序无效");
                    }
                    if (np.equals(nid)) throw new IOException("分支循环");
                    JSONArray imgs = n.optJSONArray("images");
                    if (imgs != null) {
                        for (int k = 0; k < imgs.length(); k++) {
                            String mapped = imageNames.get(imgs.getString(k));
                            if (mapped == null) throw new IOException("缺少图片");
                            imgs.put(k, mapped);
                        }
                    }
                }
                String tip = b.optString("tip");
                if (!tip.isEmpty() && !nodeIds.contains(tip)) {
                    throw new IOException("当前节点无效");
                }
            }
            imported.put(b);
        }
        JSONArray target = array(db, "items");
        for (int i = 0; i < imported.length(); i++) {
            target.put(imported.getJSONObject(i));
        }
        save();
    }

    @Override protected void onActivityResult(int request, int result,
                                               Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_IMAGE) {
            if (busy || book() == null) return;
            try { imageEditor(decodeImage(uri)); }
            catch (Exception e) { toast("读取图片失败：" + e.getMessage()); }
        } else if (request == EXPORT) {
            try {
                JSONObject snapshot = backup();
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("无法写入文件");
                    out.write(snapshot.toString().getBytes(StandardCharsets.UTF_8));
                }
                toast("备份完成，不包含密钥");
            } catch (Exception e) { toast("导出失败：" + e.getMessage()); }
        } else if (request == IMPORT) {
            try {
                JSONObject imported;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("无法读取文件");
                    imported = new JSONObject(new String(
                            readAll(in, 50 * 1024 * 1024),
                            StandardCharsets.UTF_8));
                }
                importBackup(imported);
                pending.clear();
                folder = "";
                showShelf();
                toast("已合并导入。使用本机模型配置；密钥未导入。");
            } catch (Exception e) { toast("导入失败：" + e.getMessage()); }
        }
    }

    @Override public void onBackPressed() {
        if (busy) {
            new AlertDialog.Builder(this).setTitle("停止生成？")
                    .setNegativeButton("继续等待", null)
                    .setPositiveButton("停止", (d, w) -> stopRequest()).show();
            return;
        }
        if (!bookId.isEmpty()) {
            if (flowMode) { flowMode = false; showChat(); return; }
            JSONObject b = book();
            folder = b == null ? "" : b.optString("parent");
            pending.clear();
            showShelf();
        } else if (!folder.isEmpty()) {
            JSONObject f = item(folder);
            folder = f == null ? "" : f.optString("parent");
            showShelf();
        } else super.onBackPressed();
    }

    @Override protected void onPause() {
        super.onPause();
        save();
    }

    @Override protected void onDestroy() {
        cancelled = true;
        HttpURLConnection c = connection;
        if (c != null) workerStop(c);
        worker.shutdownNow();
        super.onDestroy();
    }
}
