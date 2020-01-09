package cc.fovea.openwith;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Handle serialization of Android objects ready to be sent to javascript.
 */
class Serializer {

    /** Convert an intent to JSON.
     *
     * This actually only exports stuff necessary to see file content
     * (streams or clip data) sent with the intent.
     * If none are specified, null is return.
     */
    static JSONObject toJSONObject(
            final ContentResolver contentResolver,
            final Intent intent)
            throws JSONException {
        JSONArray itemsFromClipData = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            itemsFromClipData = itemsFromClipData(contentResolver, intent.getClipData());
        }
        JSONObject itemsFromExtras = itemsFromExtras(intent.getExtras());
        JSONArray itemsFromData = itemsFromData(contentResolver, intent.getData());

        final JSONObject intentJSON = new JSONObject();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if(itemsFromClipData != null) {
                intentJSON.put("clipItems", itemsFromClipData);
            }
        }

        intentJSON.put("type", intent.getType());
        intentJSON.put("extras", itemsFromExtras);
        intentJSON.put("action", translateAction(intent.getAction()));
        intentJSON.put("categories", intent.getCategories());
        intentJSON.put("flags", intent.getFlags());
        intentJSON.put("component", intent.getComponent());
        intentJSON.put("data", itemsFromData);
        intentJSON.put("package", intent.getPackage());
        intentJSON.put("exit", readExitOnSent(intent.getExtras()));
        return intentJSON;
    }

    private static String translateAction(final String action) {
        if ("android.intent.action.SEND".equals(action) ||
            "android.intent.action.SEND_MULTIPLE".equals(action)) {
            return "SEND";
        } else if ("android.intent.action.VIEW".equals(action)) {
            return "VIEW";
        }
        return action;
    }

    /** Read the value of "exit_on_sent" in the intent's extra.
     *
     * Defaults to false. */
    private static boolean readExitOnSent(final Bundle extras) {
        if (extras == null) {
            return false;
        }
        return extras.getBoolean("exit_on_sent", false);
    }

    /** Extract the list of items from clip data (if available).
     *
     * Defaults to null. */
    private static JSONArray itemsFromClipData(
            final ContentResolver contentResolver,
            final ClipData clipData)
            throws JSONException {
        if (clipData != null) {
            final int clipItemCount = clipData.getItemCount();
            JSONObject[] items = new JSONObject[clipItemCount];
            for (int i = 0; i < clipItemCount; i++) {
                ClipData.Item item = clipData.getItemAt(i);
                items[i] = toJSONObject(contentResolver, item.getUri());
                items[i].put("htmlText", item.getHtmlText());
                items[i].put("intent", item.getIntent());
                items[i].put("text", item.getText());
                items[i].put("uri", item.getUri());
            }
            return new JSONArray(items);
        }
        return null;
    }

    /** Extract the list of items from the intent's extra stream.
     *
     * See Intent.EXTRA_STREAM for details. */
    private static JSONObject itemsFromExtras(
            final Bundle extras)
            throws JSONException {
        if (extras == null) {
            return null;
        }
        return (JSONObject) toJsonValue(extras);
    }

    private static Object toJsonValue(final Object value) throws JSONException {
        if (value == null) {
            return null;
        } else if (value instanceof Bundle) {
            final Bundle bundle = (Bundle) value;
            final JSONObject result = new JSONObject();
            for (final String key : bundle.keySet()) {
                result.put(key, toJsonValue(bundle.get(key)));
            }
            return result;
        } else if (value.getClass().isArray()) {
            final JSONArray result = new JSONArray();
            int length = Array.getLength(value);
            for (int i = 0; i < length; ++i) {
                result.put(i, toJsonValue(Array.get(value, i)));
            }
            return result;
        } else if (
                value instanceof String
                        || value instanceof Boolean
                        || value instanceof Integer
                        || value instanceof Long
                        || value instanceof Double) {
            return value;
        } else {
            return String.valueOf(value);
        }
    }

    /** Extract the list of items from the intent's getData
     *
     * See Intent.ACTION_VIEW for details. */
    private static JSONArray itemsFromData(
            final ContentResolver contentResolver,
            final Uri uri)
            throws JSONException {
        if (uri == null) {
            return null;
        }
        final JSONObject item = toJSONObject(
                contentResolver,
                uri);
        if (item == null) {
            return null;
        }
        final JSONObject[] items = new JSONObject[1];
        items[0] = item;
        return new JSONArray(items);
    }

    /** Convert an Uri to JSON object.
     *
     * Object will include:
     *    "type" of data;
     *    "uri" itself;
     *    "path" to the file, if applicable.
     *    "data" for the file.
     */
    private static JSONObject toJSONObject(
            final ContentResolver contentResolver,
            final Uri uri)
            throws JSONException {
        final JSONObject json = new JSONObject();
        if (uri != null) {
            MimeTypeMap mime = MimeTypeMap.getSingleton();
            final String type = contentResolver.getType(uri);
            final String extension = mime.getExtensionFromMimeType(type);
            json.put("type", type);
            json.put("uri", uri);
            json.put("path", getRealPathFromURI(contentResolver, uri));
            json.put("extension", extension);
        }
        return json;
    }

    /** Return data contained at a given Uri as Base64. Defaults to null. */
    static String getDataFromURI(
            final ContentResolver contentResolver,
            final Uri uri) {
        try {
            final InputStream inputStream = contentResolver.openInputStream(uri);
            if (inputStream == null) {
                return "";
            }
            final byte[] bytes = ByteStreams.toByteArray(inputStream);
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        }
        catch (IOException e) {
            return "";
        }
    }

	/** Convert the Uri to the direct file system path of the image file.
     *
     * source: https://stackoverflow.com/questions/20067508/get-real-path-from-uri-android-kitkat-new-storage-access-framework/20402190?noredirect=1#comment30507493_20402190 */
	private static String getRealPathFromURI(
            final ContentResolver contentResolver,
            final Uri uri) {
		final String[] proj = { MediaStore.Images.Media.DATA };
		final Cursor cursor = contentResolver.query(uri, proj, null, null, null);
		if (cursor == null) {
			return "";
		}
		final int column_index = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
		if (column_index < 0) {
			cursor.close();
			return "";
		}
		cursor.moveToFirst();
		final String result = cursor.getString(column_index);
		cursor.close();
		return result;
	}
}
// vim: ts=4:sw=4:et
