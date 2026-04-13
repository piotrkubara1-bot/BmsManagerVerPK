package com.piotrek.bmsmobileviewer;

import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private EditText baseUrlInput;
    private TextView statusText;
    private TextView latestText;
    private Button refreshButton;
    private Button saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        baseUrlInput = findViewById(R.id.baseUrlInput);
        statusText = findViewById(R.id.statusText);
        latestText = findViewById(R.id.latestText);
        refreshButton = findViewById(R.id.refreshButton);
        saveButton = findViewById(R.id.saveButton);

        baseUrlInput.setText(loadBaseUrl());

        saveButton.setOnClickListener(view -> {
            String baseUrl = normalizedBaseUrl();
            saveBaseUrl(baseUrl);
            statusText.setText("Saved API URL: " + baseUrl);
        });

        refreshButton.setOnClickListener(view -> refreshData());

        refreshData();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    private void refreshData() {
        final String baseUrl = normalizedBaseUrl();
        statusText.setText("Loading...");
        latestText.setText("");

        worker.execute(() -> {
            try {
                String health = fetchText(baseUrl + "/api/health");
                String latest = fetchText(baseUrl + "/api/latest");

                JSONObject healthJson = new JSONObject(health);
                JSONArray latestArray = new JSONArray(latest);
                String summary = buildLatestSummary(latestArray);

                runOnUiThread(() -> {
                    String dbState = healthJson.optBoolean("dbConnected") ? "OK" : "OFF";
                    statusText.setText("Service: " + healthJson.optString("status") + " | DB: " + dbState);
                    latestText.setText(summary);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    statusText.setText("Failed to load data: " + ex.getMessage());
                    latestText.setText("Check Wi-Fi, phone access to PC and backend URL.");
                });
            }
        });
    }

    private String buildLatestSummary(JSONArray latestArray) {
        if (latestArray.length() == 0) {
            return "No telemetry yet.";
        }

        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < latestArray.length(); i++) {
            JSONObject item = latestArray.optJSONObject(i);
            if (item == null) {
                continue;
            }

            if (rows.length() > 0) {
                rows.append("\n");
            }
            rows.append("Module ").append(item.optInt("moduleId")).append("\n");
            rows.append("Voltage: ").append(item.optDouble("voltageV")).append(" V\n");
            rows.append("Current: ").append(item.optDouble("currentA")).append(" A\n");
            rows.append("SOC: ").append(item.optDouble("socPercent")).append(" %\n");
            rows.append("Status: ").append(item.optInt("statusCode")).append("\n");
        }
        return rows.toString();
    }

    private String fetchText(String urlValue) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Accept", "application/json");

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
            }
            return text.toString();
        } finally {
            connection.disconnect();
        }
    }

    private String normalizedBaseUrl() {
        String raw = baseUrlInput.getText() == null ? "" : baseUrlInput.getText().toString().trim();
        if (raw.endsWith("/")) {
            return raw.substring(0, raw.length() - 1);
        }
        return raw;
    }

    private String loadBaseUrl() {
        return getSharedPreferences("bms_mobile_viewer", Context.MODE_PRIVATE)
            .getString("base_url", "http://192.168.1.100:8090");
    }

    private void saveBaseUrl(String baseUrl) {
        getSharedPreferences("bms_mobile_viewer", Context.MODE_PRIVATE)
            .edit()
            .putString("base_url", baseUrl)
            .apply();
    }
}
