package com.sd.facultyfacialrecognition;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.media.Image;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;

import java.nio.ByteBuffer;

public class YuvToRgbConverter {
    private final RenderScript rs;
    private ScriptIntrinsicYuvToRGB scriptYuvToRgb;
    private Type.Builder yuvType, rgbaType;
    private Allocation in, out;

    public YuvToRgbConverter(Context context) {
        rs = RenderScript.create(context);
    }

    public void yuvToRgb(Image image, Bitmap output) {
        if (scriptYuvToRgb == null) {
            scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));
        }

        byte[] nv21 = yuv420ToNv21(image);

        if (yuvType == null) {
            yuvType = new Type.Builder(rs, Element.U8(rs)).setX(nv21.length);
            in = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);

            rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs))
                    .setX(image.getWidth())
                    .setY(image.getHeight());
            out = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);
        }

        in.copyFrom(nv21);
        scriptYuvToRgb.setInput(in);
        scriptYuvToRgb.forEach(out);
        out.copyTo(output);
    }

    private byte[] yuv420ToNv21(Image image) {
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        return nv21;
    }

    public static Bitmap yuvToRgb(byte[] nv21, int width, int height) {
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        return output;
    }
}