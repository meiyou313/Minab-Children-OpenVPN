/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import android.content.ContentProvider;
import android.content.ContentProvider.PipeDataWriter;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import de.blinkt.openvpn.core.VpnStatus;

/**
 * A very simple content provider that can serve arbitrary asset files from
 * our .apk.
 */
public class FileProvider extends ContentProvider
implements PipeDataWriter<InputStream> {
	@Override
	public boolean onCreate() {
		return true;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
			String sortOrder) {
		try {
			File dumpfile = getFileFromURI(uri);


			MatrixCursor c = new MatrixCursor(projection);

			Object[] row = new Object[projection.length];
			int i=0;
			for (String r:projection) {
				if(r.equals(OpenableColumns.SIZE))
					row[i] = dumpfile.length();
				if(r.equals(OpenableColumns.DISPLAY_NAME))
					row[i] = dumpfile.getName();
				i++;
			}
			c.addRow(row);
			return c;
		} catch (FileNotFoundException e) {
            VpnStatus.logException(e);
            return null;
		}


	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		// Don't support inserts.
		return null;
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		// Don't support deletes.
		return 0;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		// Don't support updates.
		return 0;
	}

	@Override
	public String getType(Uri uri) {
		String path = uri == null ? null : uri.getPath();
		if (path != null) {
			String lower = path.toLowerCase(java.util.Locale.US);
			if (lower.endsWith(".zip")) return "application/zip";
			if (lower.endsWith(".ovpn")) return "application/x-openvpn-profile";
			if (lower.endsWith(".txt") || lower.endsWith(".log")) return "text/plain";
		}
		return "application/octet-stream";
	}

	@Override
	public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
		File dumpfile = getFileFromURI(uri);

		try {

			InputStream is = new FileInputStream(dumpfile);
			// Start a new thread that pipes the stream data back to the caller.
			return new AssetFileDescriptor(
					openPipeHelper(uri, null, null, is, this), 0,
					dumpfile.length());
		} catch (IOException e) {
            throw new FileNotFoundException("Unable to open minidump " + uri);
		}
	}

	private File getFileFromURI(Uri uri) throws FileNotFoundException {
		// Try to open an asset with the given name.
		String path = uri.getPath();
		if(path.startsWith("/"))
			path = path.replaceFirst("/", "");       

		// Minab v0.9.8: serve dumps, config-share zips, single .ovpn files,
		// and debug .txt logs using strict single-filename validation.
		if (path.contains("..") || path.contains("/") || path.contains("\\")
				|| !path.matches("^[0-9A-Za-z._-]+\\.(dmp|dmp\\.log|zip|ovpn|txt)$"))
			throw new FileNotFoundException("url not in expect format " + uri);
		File cachedir = getContext().getCacheDir();
        return new File(cachedir,path);
	}

	@Override
	public void writeDataToPipe(ParcelFileDescriptor output, Uri uri, String mimeType,
			Bundle opts, InputStream args) {
		// Transfer data from the asset to the pipe that the client is reading.
		byte[] buffer = new byte[8192];
		int n;
		FileOutputStream fout = new FileOutputStream(output.getFileDescriptor());
		try {
			while ((n=args.read(buffer)) >= 0) {
				fout.write(buffer, 0, n);
			}
		} catch (IOException e) {
			Log.i("OpenVPNFileProvider", "Failed transferring", e);
		} finally {
			try {
				args.close();
			} catch (IOException e) {
			}
			try {
				fout.close();
			} catch (IOException e) {
			}
		}
	}
}
