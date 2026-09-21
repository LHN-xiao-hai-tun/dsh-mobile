package app.dshmobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 服务器地址设置页。
 *
 * 只存用户自己填的地址，不预置任何服务器。
 * 下方列出「最近连接」历史（最多 5 条，纯本机），点一下即可填入。
 *
 * ⚠️ 文案约定：所有「用户可见」文字一律取自 strings.xml，代码里不写中文字面量。
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText(R.string.settings_conn_title);
        title.setTextSize(20);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setTextSize(13);
        hint.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
        hint.setText(R.string.settings_conn_hint);
        root.addView(hint);

        EditText et = new EditText(this);
        et.setHint(R.string.settings_url_hint);
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setSingleLine(true);
        et.setText(Prefs.url(this));
        root.addView(et);

        // 最近连接：点一下填入（只读本机记录，不联网）
        List<String> history = Prefs.history(this);
        if (!history.isEmpty()) {
            TextView label = new TextView(this);
            label.setTextSize(13);
            label.setAlpha(0.6f);
            label.setPadding(0, (int) (18 * d), 0, (int) (4 * d));
            label.setText(R.string.settings_history_label);
            root.addView(label);

            for (final String h : history) {
                TextView row = new TextView(this);
                row.setText(h);
                row.setTextSize(14);
                row.setTypeface(Typeface.MONOSPACE);
                row.setPadding((int) (8 * d), (int) (10 * d), (int) (8 * d), (int) (10 * d));
                row.setOnClickListener(v -> {
                    et.setText(h);
                    et.setSelection(et.getText().length());
                });
                root.addView(row);
            }
        }

        Button save = new Button(this);
        save.setText(R.string.settings_save);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * d);
        save.setLayoutParams(lp);
        save.setOnClickListener(v -> {
            String u = Prefs.normalize(et.getText().toString());
            if (u.isEmpty()) {
                Toast.makeText(this, R.string.settings_toast_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            Prefs.setUrl(this, u);
            Prefs.pushHistory(this, u);
            Toast.makeText(this, R.string.settings_toast_saved, Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(save);

        Button clear = new Button(this);
        clear.setText(R.string.settings_clear);
        clear.setOnClickListener(v -> {
            Prefs.clearUrl(this);
            et.setText("");
            Toast.makeText(this, R.string.settings_toast_cleared, Toast.LENGTH_SHORT).show();
        });
        root.addView(clear);

        // 「关于」入口：版本 / 许可 / 致谢 / 检查更新
        Button about = new Button(this);
        about.setText(R.string.about_heading);
        about.setAllCaps(false);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = (int) (28 * d);
        about.setLayoutParams(alp);
        about.setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
        root.addView(about);

        setContentView(scroll);
    }
}
