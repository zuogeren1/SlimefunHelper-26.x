package me.matl114.managers;

import com.mojang.blaze3d.platform.InputConstants;

public class InputState {
    int status = -1;

    public synchronized void setPressed(boolean pressed) {
        status = pressed ? InputConstants.PRESS : InputConstants.RELEASE;
    }

    public synchronized boolean isPressed() {
        return status == InputConstants.PRESS;
    }
}
