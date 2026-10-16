package com.example.encephalon.client;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Smooth (Catmull-Rom bicubic, alpha-premultiplied) enlargement of small mod logos, done once on the CPU. Drawing a 32x32
 * logo at 190 px with the GPU's bilinear filter gives big blocky blurs; enlarging it properly first and then drawing it
 * at about 1:1 looks much cleaner.
 */
final class LogoUpscaler {
    /** Logos smaller than this (longest side, pixels) are enlarged to about this size. */
    private static final int TARGET = 512;
    private static final int MAX_FACTOR = 16;

    private LogoUpscaler() {
    }

    /** Returns the same image if it is already big enough, otherwise a new, larger one (the caller closes the old one). */
    static NativeImage smooth(NativeImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int big = Math.max(w, h);
        if (big >= TARGET) {
            return src;
        }
        int f = Math.min(MAX_FACTOR, (TARGET + big - 1) / big);
        if (f < 2) {
            return src;
        }
        float[] pre = new float[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = src.getPixelRGBA(x, y); // ABGR
                float a = (c >>> 24) & 255;
                float af = a / 255F;
                int i = (y * w + x) * 4;
                pre[i] = (c & 255) * af;
                pre[i + 1] = ((c >> 8) & 255) * af;
                pre[i + 2] = ((c >> 16) & 255) * af;
                pre[i + 3] = a;
            }
        }
        int ow = w * f;
        int oh = h * f;
        int[][] xi = new int[ow][4];
        float[][] xw = new float[ow][4];
        int[][] yi = new int[oh][4];
        float[][] yw = new float[oh][4];
        taps(xi, xw, w, f);
        taps(yi, yw, h, f);
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, ow, oh, false);
        float[] sum = new float[4];
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                sum[0] = 0;
                sum[1] = 0;
                sum[2] = 0;
                sum[3] = 0;
                for (int ky = 0; ky < 4; ky++) {
                    float wy = yw[y][ky];
                    int row = yi[y][ky] * w;
                    for (int kx = 0; kx < 4; kx++) {
                        float wt = wy * xw[x][kx];
                        int i = (row + xi[x][kx]) * 4;
                        sum[0] += pre[i] * wt;
                        sum[1] += pre[i + 1] * wt;
                        sum[2] += pre[i + 2] * wt;
                        sum[3] += pre[i + 3] * wt;
                    }
                }
                float a = Math.max(0F, Math.min(255F, sum[3]));
                int pixel = 0;
                if (a > 0.5F) {
                    float inv = 255F / a;
                    int r = clamp(sum[0] * inv);
                    int g = clamp(sum[1] * inv);
                    int b = clamp(sum[2] * inv);
                    pixel = (Math.round(a) << 24) | (b << 16) | (g << 8) | r;
                }
                out.setPixelRGBA(x, y, pixel);
            }
        }
        return out;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    /** For each destination pixel, the four source pixels around it and their cubic weights. */
    private static void taps(int[][] idx, float[][] wt, int srcN, int f) {
        for (int d = 0; d < idx.length; d++) {
            float s = (d + 0.5F) / f - 0.5F;
            int base = (int) Math.floor(s);
            float t = s - base;
            for (int k = 0; k < 4; k++) {
                int o = k - 1;
                idx[d][k] = Math.max(0, Math.min(srcN - 1, base + o));
                wt[d][k] = cubic(t - o);
            }
        }
    }

    /** Catmull-Rom kernel. */
    private static float cubic(float x) {
        x = Math.abs(x);
        if (x < 1F) {
            return 1.5F * x * x * x - 2.5F * x * x + 1F;
        }
        if (x < 2F) {
            return -0.5F * x * x * x + 2.5F * x * x - 4F * x + 2F;
        }
        return 0F;
    }
}
