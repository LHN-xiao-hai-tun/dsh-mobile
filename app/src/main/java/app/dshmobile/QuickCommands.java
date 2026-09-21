package app.dshmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 快捷指令面板：一组**可编辑**的常用提示词，点一下填进 DSH 的输入框。
 *
 * ⛔ 安全约束（刻意保守）：
 *   1. 只「填入」，**绝不代替你发送** —— 不派发 Enter / 不 submit；
 *   2. 输入框已有内容时**追加**而不是覆盖，绝不吞掉你正在打的东西；
 *   3. 找不到输入框（弹层/iframe/结构变了）就退化为**复制到剪贴板**并提示，
 *      不做任何猜测性的盲点点击。
 *
 * ⚠️ 文案约定：所有「用户可见」文字一律取自 strings.xml，代码里不写中文字面量。
 */
final class QuickCommands {

    private QuickCommands() {
    }

    static void show(final Activity a, final WebView web) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(R.string.qc_title)
                .setView(box)
                .setNegativeButton(R.string.qc_close, null)
                .setNeutralButton(R.string.qc_restore, null)
                .create();

        fill(a, web, dlg, box);

        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            Prefs.resetTemplates(a);
            fill(a, web, dlg, box);
            Toast.makeText(a, R.string.qc_restored, Toast.LENGTH_SHORT).show();
        }));

        dlg.show();
    }

    /** 重建列表（编辑 / 恢复默认后调用） */
    private static void fill(final Activity a, final WebView web,
                             final AlertDialog dlg, LinearLayout box) {
        float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);
        box.setPadding(pad, (int) (12 * d), pad, (int) (4 * d));
        box.removeAllViews();

        TextView tip = new TextView(a);
        tip.setTextSize(12);
        tip.setAlpha(0.6f);
        tip.setPadding(0, 0, 0, (int) (10 * d));
        tip.setText(R.string.qc_tip);
        box.addView(tip);

        final List<String> tpls = Prefs.templates(a);
        for (int i = 0; i < tpls.size(); i++) {
            final int index = i;
            final String text = tpls.get(i);

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = (int) (10 * d);
            row.setLayoutParams(rlp);

            final TextView label = new TextView(a);
            label.setText(text.isEmpty() ? a.getString(R.string.qc_empty) : text);
            label.setTextSize(15);
            label.setPadding(0, (int) (12 * d), (int) (8 * d), (int) (12 * d));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(llp);
            label.setOnClickListener(v -> {
                String cur = Prefs.templates(a).get(index);
                if (cur.trim().isEmpty()) {
                    Toast.makeText(a, R.string.qc_empty_warn, Toast.LENGTH_SHORT).show();
                    return;
                }
                inject(a, web, cur, dlg);
            });
            row.addView(label);

            Button edit = new Button(a);
            edit.setText(R.string.qc_edit);
            edit.setAllCaps(false);
            edit.setMinWidth((int) (52 * d));
            edit.setOnClickListener(v -> editTemplate(a, dlg, box, web, index));
            row.addView(edit);

            box.addView(row);
        }
    }

    private static void editTemplate(final Activity a, final AlertDialog dlg,
                                     final LinearLayout box, final WebView web, final int index) {
        float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);

        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2);
        input.setPadding(pad, pad, pad, pad);
        input.setText(Prefs.templates(a).get(index));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(a)
                .setTitle(R.string.qc_edit_title)
                .setView(input)
                .setPositiveButton(R.string.qc_save, (dd, w) -> {
                    Prefs.setTemplate(a, index, input.getText().toString());
                    fill(a, web, dlg, box);
                })
                .setNegativeButton(R.string.qc_cancel, null)
                .show();
    }

    /** 把指令填入 WebView 输入框；失败则复制到剪贴板。 */
    private static void inject(final Activity a, final WebView web, final String text,
                               final AlertDialog dlg) {
        if (web == null) {
            copy(a, text);
            Toast.makeText(a, R.string.qc_copied, Toast.LENGTH_SHORT).show();
            return;
        }
        web.evaluateJavascript(buildJs(text), value -> {
            boolean ok = value != null && value.contains("OK");
            if (ok) {
                Toast.makeText(a, R.string.qc_injected, Toast.LENGTH_SHORT).show();
            } else {
                copy(a, text);
                Toast.makeText(a, R.string.qc_no_input, Toast.LENGTH_SHORT).show();
            }
            if (dlg != null && dlg.isShowing()) {
                dlg.dismiss();
            }
        });
    }

    /**
     * 注入脚本：找出最可能的输入框并把文本追加进去。
     * 只派发 input/change，**不派发任何按键或 submit** —— 不会误触发发送。
     */
    private static String buildJs(String text) {
        String lit = org.json.JSONObject.quote(text);
        return "(function(){try{"
                + "var t=" + lit + ";"
                + "function vis(e){if(!e)return false;var r=e.getBoundingClientRect();"
                + "if(r.width<=0||r.height<=0)return false;var s=getComputedStyle(e);"
                + "return s.visibility!=='hidden'&&s.display!=='none';}"
                + "var c=[];"
                + "document.querySelectorAll('textarea').forEach(function(e){if(vis(e))c.push(e);});"
                + "if(!c.length){document.querySelectorAll('[contenteditable=\"true\"]')"
                + ".forEach(function(e){if(vis(e))c.push(e);});}"
                + "if(!c.length){document.querySelectorAll('input[type=\"text\"],input:not([type])')"
                + ".forEach(function(e){if(vis(e))c.push(e);});}"
                + "if(!c.length)return 'NO_INPUT';"
                + "var el=c[c.length-1];"
                + "try{el.focus();}catch(e){}"
                + "if(el.isContentEditable){"
                + "el.textContent=(el.textContent&&el.textContent.trim())?el.textContent+'\\n'+t:t;}"
                + "else{el.value=(el.value&&el.value.trim())?el.value+'\\n'+t:t;}"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return 'OK';}catch(e){return 'ERR';}})()";
    }

    private static void copy(Activity a, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(a.getString(R.string.qc_clip_label), text));
            }
        } catch (Exception ignored) {
        }
    }
}
