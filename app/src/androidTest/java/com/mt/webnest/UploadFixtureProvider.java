package com.mt.webnest;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class UploadFixtureProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return uri.getPath().startsWith("/image") ? "image/png" : "text/plain";
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {"fixture.txt", 8});
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Read only");
        }
        File file = new File(getContext().getCacheDir(), "upload-fixture.txt");
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (uri.getPath().startsWith("/image")) {
                Bitmap image = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888);
                for (int y = 0; y < 100; y++) {
                    for (int x = 0; x < 300; x++) {
                        image.setPixel(
                                x,
                                y,
                                uri.getPath().equals("/image-alpha")
                                        ? Color.argb(128, 255, 255, 255)
                                        : y < 20 ? Color.WHITE
                                        : x < 100 ? Color.RED
                                        : x < 200 ? Color.GREEN
                                        : Color.BLUE);
                    }
                }
                image.compress(Bitmap.CompressFormat.PNG, 100, out);
                image.recycle();
            } else {
                out.write("Uploaded".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
