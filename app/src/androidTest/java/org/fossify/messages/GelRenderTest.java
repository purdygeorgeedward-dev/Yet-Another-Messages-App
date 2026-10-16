package org.fossify.messages;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.fossify.messages.extensions.GelBubbleKt;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GelRenderTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private void surface(Canvas canvas, int color, boolean pale, boolean round, boolean inset, int x, int y, int w, int h) {
        Drawable d = GelBubbleKt.createGelSurfaceDrawable(context, color, pale, round, inset);
        d.setBounds(x, y, x + w, y + h);
        d.draw(canvas);
    }
    private void text(Canvas canvas, String value, int color, float size, int x, int y, int width) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextSize(size);
        if (color == Color.WHITE) paint.setShadowLayer(1.7f, 0, 1, 0x66002550);
        StaticLayout layout = StaticLayout.Builder.obtain(value, 0, value.length(), paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }
    @Test public void renderPhoneMaterialsAndVariableMessageSizes() throws Exception {
        Bitmap image = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(0xFFF6FAFF);
        int blue = 0xFF0084E8, slate = 0xFF607D9A, pink = 0xFFEF006B, ice = 0xFF48BEF5;
        surface(canvas, ice, true, false, false, 8, 8, 704, 110);
        text(canvas, "\u2039   ChatGPT", 0xFF003E73, 38, 36, 34, 500);
        text(canvas, "\u260e  \u22ee", 0xFF003E73, 34, 565, 35, 130);
        Bitmap original = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        original.eraseColor(pink);
        Bitmap avatar = GelBubbleKt.createGelContactIcon(context, "ChatGPT", original);
        for (int i = 0; i < 4; i++) {
            int y = 140 + 185 * i;
            canvas.drawBitmap(avatar, null, new Rect(24, y, 132, y + 108), null);
            surface(canvas, blue, false, false, false, 140, y, 470, 122);
            text(canvas, "Your message appears here.\nBright blue glass gel.", GelBubbleKt.getGelBubbleTextColor(context, blue), 28, 165, y + 30, 425);
            text(canvas, "03:" + (10 + i) + " AM", 0xFF344B63, 24, 285, y + 135, 250);
        }
        surface(canvas, slate, false, false, false, 218, 906, 484, 126);
        text(canvas, "A glossy, rounded\nmessage bubble.", Color.WHITE, 28, 248, 938, 420);
        surface(canvas, ice, true, false, false, 8, 1100, 704, 166);
        surface(canvas, ice, true, true, false, 24, 1135, 100, 108);
        text(canvas, "+", 0xFF003E73, 60, 54, 1144, 80);
        surface(canvas, 0xFFB4DAF4, true, false, true, 130, 1134, 450, 110);
        text(canvas, "Type a message...", 0xFF596F84, 27, 160, 1170, 400);
        surface(canvas, blue, false, true, false, 586, 1134, 108, 110);
        text(canvas, "\u27a4", Color.WHITE, 54, 615, 1150, 90);
        File out = new File(context.getExternalFilesDir(null), "gel-render-preview.png");
        try (FileOutputStream stream = new FileOutputStream(out)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        // Exercise the actual drawable at both narrow and tall message bounds.
        for (int[] size : new int[][] {{90, 60}, {700, 120}, {350, 650}}) {
            Bitmap sample = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            Drawable drawable = GelBubbleKt.createGelBubbleDrawable(context, blue, false);
            drawable.setBounds(0, 0, size[0], size[1]);
            drawable.draw(new Canvas(sample));
            assertTrue("Message body must remain opaque after resizing",
                Color.alpha(sample.getPixel(size[0] / 2, size[1] / 2)) > 240);
            sample.recycle();
        }
    }
}
