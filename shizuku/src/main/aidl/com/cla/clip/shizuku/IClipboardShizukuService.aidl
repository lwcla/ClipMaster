// IClipboardShizukuService.aidl
package com.cla.clip.shizuku;
import com.cla.clip.shizuku.ShizukuCallback;

interface IClipboardShizukuService {
    void destroy() = 16777114; // Destroy method defined by Shizuku server
    void exit() = 1; // Exit method defined by user
    void start() = 2;
    void setCallback(ShizukuCallback callback) = 3;
}