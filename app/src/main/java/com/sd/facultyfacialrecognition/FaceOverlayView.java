package com.sd.facultyfacialrecognition;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class FaceOverlayView extends View {
    private List<FaceGraphic> faces = new ArrayList<>();
    private Paint boxPaint;
    private Paint textPaint;

    private int imageWidth = 0;
    private int imageHeight = 0;
    private boolean isFrontCamera = true;

    public FaceOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5f);
        boxPaint.setColor(Color.GREEN);

        textPaint = new Paint();
        textPaint.setTextSize(40f);
        textPaint.setColor(Color.WHITE);
    }

    public void setFaces(List<FaceGraphic> newFaces) {
        this.faces = newFaces;
        invalidate();
    }

    public void setImageSourceInfo(int width, int height, boolean isFront) {
        this.imageWidth = width;
        this.imageHeight = height;
        this.isFrontCamera = isFront;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (faces == null || faces.isEmpty() || imageWidth == 0 || imageHeight == 0) return;

        float scaleX = getWidth() / (float) imageHeight;
        float scaleY = getHeight() / (float) imageWidth;

        Matrix transform = new Matrix();
        if (isFrontCamera) {
            transform.setScale(-1, 1);
            transform.postTranslate(getWidth(), 0);
        }

        for (FaceGraphic face : faces) {
            Rect rect = face.rect;

            float left = rect.left * scaleX;
            float top = rect.top * scaleY;
            float right = rect.right * scaleX;
            float bottom = rect.bottom * scaleY;

            float[] pts = {left, top, right, bottom};
            transform.mapPoints(pts);

            float newLeft = Math.min(pts[0], pts[2]);
            float newRight = Math.max(pts[0], pts[2]);

            Rect scaledRect = new Rect((int) newLeft, (int) top, (int) newRight, (int) bottom);

            canvas.drawRect(scaledRect, boxPaint);
            canvas.drawText(face.label, scaledRect.left, Math.max(scaledRect.top - 10, 40), textPaint);
        }
    }

    public static class FaceGraphic {
        public Rect rect;
        public String label;
        public float distance;

        public FaceGraphic(Rect rect, String label, float distance) {
            this.rect = rect;
            this.label = label;
            this.distance = distance;
        }
    }
}