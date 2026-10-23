package me.matl114.utils.entity;

import lombok.*;
import lombok.experimental.Accessors;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.hooks.ViaProtocols;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.versioned.SupportVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.phys.Vec2;

public class PlayerInputUtils {
    public static Input of(LocalPlayer player) {
        return new Input(player.input.keyPresses);
    }

    public static Input of(net.minecraft.client.player.ClientInput input) {
        return new Input(input.keyPresses);
    }

    public static Input of(net.minecraft.world.entity.player.Input input) {
        return new Input(input);
    }

    public static Input of(Options options) {
        return new Input(
                options.keyUp.isDown(),
                options.keyDown.isDown(),
                options.keyLeft.isDown(),
                options.keyRight.isDown(),
                options.keyJump.isDown(),
                options.keyShift.isDown(),
                options.keySprint.isDown());
    }

    public static Input of(ServerboundPlayerInputPacket packet) {
        return of(packet.input());
    }

    public static Input tryCorrectMovementInput(Input input, float originalYaw, float currentYaw) {
        float diff = EntityUtils.getSafeYawDiff(originalYaw, currentYaw);
        int forwardSpeed;
        int sidewaySpeed;
        int movementForward = input.forwardSpeed();
        int movementSideways = input.sidewaysSpeed();
        if (diff < 22.5 && diff >= -22.5) {
            // do nothing
            return input;
        } else if (diff < 67.5 && diff >= 22.5) {
            // turn to
            forwardSpeed = (movementForward - movementSideways);
            sidewaySpeed = (movementForward + movementSideways);
        } else if (diff >= 67.5 && diff < 90.0F + 22.5F) {
            forwardSpeed = -movementSideways;
            sidewaySpeed = movementForward;
        } else if (diff >= 90.0F + 22.5F && diff < 90.0F + 67.5F) {
            forwardSpeed = (-movementForward - movementSideways);
            sidewaySpeed = (movementForward - movementSideways);
        } else if (diff >= 90.0F + 67.5F || diff < -90.0F - 67.5F) {
            forwardSpeed = -movementForward;
            sidewaySpeed = -movementSideways;
        } else if (diff >= -90.0F - 67.5F && diff < -90.0F - 22.5F) {
            forwardSpeed = (-movementForward + movementSideways);
            sidewaySpeed = (-movementForward - movementSideways);
        } else if (diff >= -90.0F - 22.5F && diff < -90.0F + 22.5F) {
            forwardSpeed = movementSideways;
            sidewaySpeed = -movementForward;
        } else if (diff >= -90.0F + 22.5F && diff < -22.5F) {
            forwardSpeed = (movementForward + movementSideways);
            sidewaySpeed = (-movementForward + movementSideways);
        } else {
            return input;
        }
        // sync values
        return new Input(
                forwardSpeed > 0,
                forwardSpeed < 0,
                sidewaySpeed > 0,
                sidewaySpeed < 0,
                input.jump(),
                input.sneak(),
                input.sprint());
    }

    public static final Input EMPTY = new Input(false, false, false, false, false, false, false);

    @AllArgsConstructor
    @Accessors(fluent = true, chain = true)
    @Setter
    @Getter
    @With
    @EqualsAndHashCode
    public static class Input implements Cloneable {
        boolean forward;
        boolean backward;
        boolean left;
        boolean right;
        boolean jump;
        boolean sneak;
        boolean sprint;

        public Input(net.minecraft.world.entity.player.Input input) {
            this(
                    input.forward(),
                    input.backward(),
                    input.left(),
                    input.right(),
                    input.jump(),
                    input.shift(),
                    input.sprint());
        }

        public Input(boolean forward, boolean backward, boolean left, boolean right) {
            this(forward, backward, left, right, false, false, false);
        }

        public net.minecraft.world.entity.player.Input toPlayerInput() {
            return new net.minecraft.world.entity.player.Input(
                    this.forward, this.backward, this.left, this.right, this.jump, this.sneak, this.sprint);
        }

        public ServerboundPlayerInputPacket toPlayerInputPacket() {
            return new ServerboundPlayerInputPacket(toPlayerInput());
        }

        public Input sendPlayerInputPacket() {
            Minecraft.getInstance().getConnection().send(toPlayerInputPacket());
            return this;
        }

        public Input sendPlayerInputAsRiding() {
            if (ViaFabricPlusHooks.getInstance().isViaEnabled()
                    && ViaFabricPlusHooks.getInstance().getCurrentVersion().isLowerOrEqualTo(21, 1)) {
                // 1.21.1 ride packet
                ViaFabricPlusHooks.ViaPacketWrapper wrapper =
                        ViaFabricPlusHooks.getInstance().createViaPacket();
                wrapper.writePacketType(ViaProtocols.V1_20_3_TO_1_20_5, GamePacketTypes.SERVERBOUND_PLAYER_INPUT);
                wrapper.write("FLOAT", sidewaysSpeed() * 0.98F);
                wrapper.write("FLOAT", forwardSpeed() * 0.98F);
                byte b = 0;
                if (this.jump()) {
                    b = (byte) (b | 1);
                }

                if (this.sneak()) {
                    b = (byte) (b | 2);
                }

                wrapper.write("BYTE", b);
                wrapper.scheduleSendToServer(ViaProtocols.V1_21_TO_1_21_2, true);
            } else {
                sendPlayerInputPacket();
            }
            return this;
        }

        public Input sendPlayerSneakUpdatePacket() {
            if (ViaFabricPlusHooks.getInstance().isViaEnabled()
                    && ViaFabricPlusHooks.getInstance().getCurrentVersion().isLowerOrEqualTo(21, 5)
                    && SupportVersion.CURRENT.isHigherOrEqualTo(21, 6)) {
                // send sneak packet
                ViaFabricPlusHooks.ViaPacketWrapper wrapper =
                        ViaFabricPlusHooks.getInstance().createViaPacket();
                wrapper.writePacketType(ViaProtocols.V1_21_4_TO_1_21_5, GamePacketTypes.SERVERBOUND_PLAYER_COMMAND);
                wrapper.write("VAR_INT", Minecraft.getInstance().player.getId());
                wrapper.write("VAR_INT", this.sneak ? 0 : 1);
                wrapper.write("VAR_INT", 0);
                // todo why doesn't work
                wrapper.sendToServer(ViaProtocols.V1_21_5_TO_1_21_6, false);
            }
            sendPlayerInputPacket();
            return this;
        }

        public int forwardSpeed() {
            return this.forward == this.backward ? 0 : (this.forward ? 1 : -1);
        }

        public int sidewaysSpeed() {
            return this.left == this.right ? 0 : (this.left ? 1 : -1);
        }

        public int upwardSpeed() {
            return this.jump == this.sneak ? 0 : (this.jump ? 1 : -1);
        }

        public Input applyInput(LocalPlayer input) {
            applyInput(input.input);
            return this;
        }

        public Input applyInput(net.minecraft.client.player.ClientInput input) {
            input.keyPresses = toPlayerInput();
            return this;
        }

        private static Vec2 compatMovementVectorWithViaFabric(Vec2 vec2f) {
            // shit,
            return ViaFabricPlusHooks.getInstance().getCurrentVersion().isLowerOrEqualTo(21, 4)
                    ? vec2f
                    : vec2f.normalized();
        }

        public void syncMovementVector(net.minecraft.client.player.ClientInput input, float iqBoost) {
            input.moveVector =
                    compatMovementVectorWithViaFabric(new Vec2(this.sidewaysSpeed(), this.forwardSpeed()));
        }

        public Input applyInput(Options options) {
            options.keyUp.setDown(forward);
            options.keyDown.setDown(backward);
            options.keyLeft.setDown(left);
            options.keyRight.setDown(right);
            options.keyJump.setDown(jump);
            options.keyShift.setDown(sneak);
            options.keySprint.setDown(sprint);
            return this;
        }

        public boolean hasMovement() {
            return forward || backward || left || right || jump;
        }

        public boolean hasWASDMovement() {
            return (forward != backward) || (left != right);
        }

        public boolean hasMovementControl() {
            return forward || backward || left || right || jump || sneak;
        }

        @Override
        public Input clone() {
            try {
                Input clone = (Input) super.clone();
                // TODO: copy mutable state here, so the clone can't change the internals of the original
                return clone;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError();
            }
        }

        @Override
        public String toString() {
            return "Input{" + "forward="
                    + forward + ", backward="
                    + backward + ", left="
                    + left + ", right="
                    + right + ", jump="
                    + jump + ", sneak="
                    + sneak + ", sprint="
                    + sprint + '}';
        }
    }
}
