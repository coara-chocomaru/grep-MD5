package com.example.grepmd5app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_FILE_REQUEST = 1;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private File selectedFile;
    private EditText grepInput;
    private TextView resultTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button selectFileButton = findViewById(R.id.selectFileButton);
        Button grepButton = findViewById(R.id.grepButton);
        Button md5Button = findViewById(R.id.md5Button);
        grepInput = findViewById(R.id.grepInput);
        resultTextView = findViewById(R.id.resultTextView);

        // 権限確認
        checkPermissions();

        // ファイル選択ボタンの処理
        selectFileButton.setOnClickListener(v -> openFilePicker());

        // grep実行ボタンの処理
        grepButton.setOnClickListener(v -> executeGrep());

        // MD5確認ボタンの処理
        md5Button.setOnClickListener(v -> checkMd5());
    }

    // 権限を確認して、必要ならリクエストする
    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            // 権限がない場合、リクエスト
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_CODE);
        }
    }

    // 権限リクエスト結果の処理
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                // 許可された場合
                resultTextView.setText("権限が許可されました。");
            } else {
                // 拒否された場合
                resultTextView.setText("権限が許可されていません。アプリの機能に制限があります。");
            }
        }
    }

    // ファイル選択のダイアログを開く
    private void openFilePicker() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_FILE_REQUEST);
        } else {
            Toast.makeText(this, "権限がありません。ファイルを選択できません。", Toast.LENGTH_SHORT).show();
        }
    }

    // ファイル選択後の処理
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK && data != null) {
            try {
                Uri selectedFileUri = data.getData();
                String fileName = getFileName(selectedFileUri); // ファイル名を取得

                File copiedFile = copyFileToAppStorage(selectedFileUri, fileName);
                selectedFile = copiedFile; // コピーしたファイルを selectedFile に設定
                Toast.makeText(this, "ファイルがアプリ専用ストレージにコピーされました", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(this, "ファイルのコピーに失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    // ファイル名を取得するメソッド
    private String getFileName(Uri uri) {
        String[] projection = { MediaStore.Images.Media.DISPLAY_NAME };
        Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
        if (cursor != null) {
            cursor.moveToFirst();
            int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            String fileName = cursor.getString(columnIndex);
            cursor.close();
            return fileName;
        }
        return null;
    }

    // SDカードからアプリ専用ストレージにファイルをコピーするメソッド
    private File copyFileToAppStorage(Uri fileUri, String fileName) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(fileUri);
        File copiedFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName);

        try (FileOutputStream outputStream = new FileOutputStream(copiedFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
        } finally {
            inputStream.close();
        }

        return copiedFile;
    }

    // grep実行メソッド
    private void executeGrep() {
        if (selectedFile == null || grepInput.getText().toString().isEmpty()) {
            resultTextView.setText("ファイルとキーワードを選択してください");
            return;
        }

        String keyword = grepInput.getText().toString();
        new GrepTask().execute(selectedFile, keyword);  // 非同期でgrep処理を実行
    }

    // grepを非同期で実行
    private class GrepTask extends AsyncTask<Object, Void, String> {
        @Override
        protected String doInBackground(Object... params) {
            File file = (File) params[0];
            String keyword = (String) params[1];
            return grepFile(file, keyword);
        }

        @Override
        protected void onPostExecute(String result) {
            resultTextView.setText(result);
            saveLog("grep_log", result);
        }
    }

    // grep処理（ファイルの各行を検索）
    private String grepFile(File file, String keyword) {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            Pattern pattern = Pattern.compile(keyword, Pattern.CASE_INSENSITIVE);  // 大文字小文字を無視する場合
            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    result.append(line).append("\n");
                }
            }
        } catch (IOException e) {
            result.append("エラー: ").append(e.getMessage());
        }
        return result.toString();
    }

    // MD5チェックの処理
    private void checkMd5() {
        if (selectedFile == null) {
            resultTextView.setText("ファイルを選択してください");
            return;
        }

        new Md5Task().execute(selectedFile); // 非同期でMD5計算を実行
    }

    // MD5計算を非同期で実行
    private class Md5Task extends AsyncTask<File, Void, String> {
        @Override
        protected String doInBackground(File... files) {
            return getMd5Checksum(files[0]);
        }

        @Override
        protected void onPostExecute(String md5) {
            resultTextView.setText("MD5: " + md5);
            saveLog("md5sum_log", md5);
        }
    }

    // MD5ハッシュを計算
    private String getMd5Checksum(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            FileInputStream fis = new FileInputStream(file);
            byte[] byteArray = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesRead);
            }
            byte[] md5Bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : md5Bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "エラー: " + e.getMessage();
        }
    }

    // ログ保存
    private void saveLog(String logType, String content) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String logFileName = logType + "_" + timestamp + ".txt";
        File logFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), logFileName);

        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(content + "\n");
        } catch (IOException e) {
            Log.e("MainActivity", "ログの保存に失敗しました", e);
        }
    }
}
