package au.com.michaelcarey.artouchpad;

import android.app.ActivityManager;
import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Point;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

import java.lang.reflect.Method;

/** Reads published Jellyfin art from the Shizuku shell process, which has no registered ActivityThread. */
final class JellyfinCover {
    private JellyfinCover() {}

    static Bitmap read(Context shell, Uri uri, int pixels) throws Exception {
        String authority = uri.getAuthority();
        int user = (int) android.os.UserHandle.class.getMethod("getIdentifier").invoke(Process.myUserHandle());
        IBinder token = new Binder();
        Class<?> managerType = Class.forName("android.app.IActivityManager");
        Object manager = ActivityManager.class.getMethod("getService").invoke(null);
        // Use the same balanced external-provider acquisition as Android's shell content client.
        Object holder = managerType.getMethod("getContentProviderExternal", String.class, int.class,
                IBinder.class, String.class).invoke(manager, authority, user, token, "playback-cover");
        if (holder == null) return null;
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            Method open = Class.forName("android.content.IContentProvider").getMethod("openTypedAssetFile",
                    AttributionSource.class, Uri.class, String.class, Bundle.class, Class.forName("android.os.ICancellationSignal"));
            Bundle options = new Bundle();
            options.putParcelable(ContentResolver.EXTRA_SIZE, new Point(pixels, pixels));
            ImageDecoder.Source source = ImageDecoder.createSource(() -> (AssetFileDescriptor)
                    open.invoke(provider, shell.getAttributionSource(), uri, "image/*", options, null));
            return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                float scale = Math.min(1f, (float) pixels / Math.max(width, height));
                decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } finally {
            managerType.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(manager, authority, token, user);
        }
    }
}
