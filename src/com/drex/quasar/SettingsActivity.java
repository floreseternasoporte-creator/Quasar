package com.drex.quasar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Quasar 2.2 — Ajustes: acerca de la app (versión, creador, paquete)
 * y gestión de datos (borrar historial).
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        View btnBack = findViewById(R.id.btn_back);
        Cine.pressFx(btnBack);
        btnBack.setOnClickListener(v -> finish());

        String version = "?";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            //noinspection deprecation
            version = pi.versionName + " (build " + pi.versionCode + ")";
        } catch (Exception ignored) {
        }
        ((TextView) findViewById(R.id.row_version_value))
                .setText(getString(R.string.label_version) + " " + version);
        ((TextView) findViewById(R.id.row_package_value)).setText(getPackageName());

        View rowClear = findViewById(R.id.row_clear_history);
        Cine.pressFx(rowClear);
        rowClear.setOnClickListener(v -> new AlertDialog.Builder(
                this, android.R.style.Theme_Material_Dialog)
                .setTitle(R.string.clear_history)
                .setMessage(R.string.clear_history_confirm)
                .setPositiveButton(R.string.clear_yes, (d, w) -> {
                    HistoryStore.clear(this);
                    Toast.makeText(this, R.string.history_cleared,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.clear_no, null)
                .show());

        Cine.enterCine(findViewById(android.R.id.content), 0);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.hold, R.anim.slide_out_down);
    }
}
