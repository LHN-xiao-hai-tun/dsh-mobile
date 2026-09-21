package app.dshmobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 服务器地址设置页。
 *
 * 只存用户自己填的地址，不预置任何服务器。
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
        title.setText("连接 DSH 服务器");
        title.setTextSize(20);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setTextSize(13);
        hint.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
        hint.setText("填入你自己部署的 DeepSeek Harness 地址。\n\n"
                + "常用形式：\n"
                + "  • 局域网：  http://<电脑IP>:3081\n"
                + "  • 本机服务：http://127.0.0.1:3080\n\n"
                + "提示：确认手机与该服务在同一网络；\n"
                + "若服务端开了访问密码，打开后会要求输入。\n");
        root.addView(hint);

        SharedPreferences sp = getSharedPreferences("dsh", MODE_PRIVATE);

        EditText et = new EditText(this);
        et.setHint("http://192.168.x.x:3081");
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setSingleLine(true);
        et.setText(sp.getString("url", ""));
        root.addView(et);

        Button save = new Button(this);
        save.setText("保存并连接");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * d);
        save.setLayoutParams(lp);
        save.setOnClickListener(v -> {
            String u = et.getText().toString().trim();
            if (u.isEmpty()) {
                Toast.makeText(this, "请填写地址", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "http://" + u;
            }
            sp.edit().putString("url", u).apply();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(save);

        Button clear = new Button(this);
        clear.setText("清除地址");
        clear.setOnClickListener(v -> {
            sp.edit().remove("url").apply();
            et.setText("");
            Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show();
        });
        root.addView(clear);

        // 「关于」入口：版本 / 许可 / 致谢 / 检查更新
        Button about = new Button(this);
        about.setText("关于 DSH Mobile");
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
