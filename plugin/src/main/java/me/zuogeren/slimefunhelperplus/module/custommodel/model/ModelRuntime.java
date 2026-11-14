package me.zuogeren.slimefunhelperplus.module.custommodel.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 一个已烘焙好的 YSM 模型：骨骼矩阵 + 每个骨骼的面片，直接往 {@link VertexConsumer} 里写顶点。
 *
 * <p>几何在反序列化阶段已经烘焙成模型空间的面片（见 {@code YsmGeometryParsing}，坐标是方块单位），
 * 这里只负责按骨骼层级乘矩阵；根的朝向（{@code 180 - bodyRot}）由调用方在 PoseStack 上施加。
 */
public final class ModelRuntime {

    private final List<RawYsmModel.RawBone> bones;
    private final Matrix4f[] boneMatrices;
    private final Matrix3f[] normalMatrices;
    private final Identifier texture;

    private ModelRuntime(List<RawYsmModel.RawBone> bones, Matrix4f[] boneMatrices,
            Matrix3f[] normalMatrices, Identifier texture) {
        this.bones = bones;
        this.boneMatrices = boneMatrices;
        this.normalMatrices = normalMatrices;
        this.texture = texture;
    }

    public static ModelRuntime build(RawYsmModel model, Identifier texture) {
        RawYsmModel.RawGeometry geometry = model.mainEntity == null ? null : model.mainEntity.mainModel;
        if (geometry == null || geometry.bones == null || geometry.bones.isEmpty()) {
            return null;
        }
        List<RawYsmModel.RawBone> bones = geometry.bones;
        int count = bones.size();
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String name = bones.get(i).name;
            if (name != null && !name.isEmpty()) {
                byName.putIfAbsent(name, i);
            }
        }
        int[] parents = new int[count];
        for (int i = 0; i < count; i++) {
            parents[i] = -1;
            String parentName = bones.get(i).parentName;
            if (parentName != null && !parentName.isEmpty()) {
                Integer parent = byName.get(parentName);
                if (parent != null && parent != i) {
                    parents[i] = parent;
                }
            }
        }
        Matrix4f[] matrices = new Matrix4f[count];
        byte[] state = new byte[count];
        for (int i = 0; i < count; i++) {
            compute(i, bones, parents, matrices, state);
        }
        Matrix3f[] normals = new Matrix3f[count];
        for (int i = 0; i < count; i++) {
            normals[i] = normalOf(matrices[i]);
        }
        int roots = 0;
        int unresolved = 0;
        for (int i = 0; i < count; i++) {
            if (parents[i] < 0) {
                roots++;
                String parentName = bones.get(i).parentName;
                if (parentName != null && !parentName.isEmpty()) {
                    unresolved++;
                }
            }
        }
        float maxCoordinate = 0.0F;
        int faces = 0;
        for (RawYsmModel.RawBone bone : bones) {
            for (RawYsmModel.RawCube cube : bone.cubes) {
                for (RawYsmModel.RawFace face : cube.faces) {
                    faces++;
                    for (float[] position : face.positions) {
                        maxCoordinate = Math.max(maxCoordinate, Math.max(Math.abs(position[0]),
                                Math.max(Math.abs(position[1]), Math.abs(position[2]))));
                    }
                }
            }
        }
        Debug.info("[SFH+] geo bones=" + count + " roots=" + roots + " unresolvedParents=" + unresolved
                + " faces=" + faces + " maxCoord=" + maxCoordinate);
        return new ModelRuntime(bones, matrices, normals, texture);
    }

    private static Matrix4f compute(int index, List<RawYsmModel.RawBone> bones, int[] parents,
            Matrix4f[] matrices, byte[] state) {
        if (state[index] == 2) {
            return matrices[index];
        }
        if (state[index] == 1) {
            // 父级成环：按根处理，避免无限递归
            return new Matrix4f();
        }
        state[index] = 1;
        Matrix4f local = localOf(bones.get(index));
        int parent = parents[index];
        Matrix4f world;
        if (parent < 0) {
            world = local;
        } else {
            world = new Matrix4f(compute(parent, bones, parents, matrices, state)).mul(local);
        }
        matrices[index] = world;
        state[index] = 2;
        return world;
    }

    private static Matrix4f localOf(RawYsmModel.RawBone bone) {
        Matrix4f matrix = new Matrix4f();
        float[] pivot = bone.pivot == null ? new float[3] : bone.pivot;
        float[] rotation = bone.rotation == null ? new float[3] : bone.rotation;
        matrix.translate(pivot[0] / 16.0F, pivot[1] / 16.0F, pivot[2] / 16.0F);
        matrix.rotateZ(rotation[2]);
        matrix.rotateY(rotation[1]);
        matrix.rotateX(rotation[0]);
        matrix.translate(-pivot[0] / 16.0F, -pivot[1] / 16.0F, -pivot[2] / 16.0F);
        return matrix;
    }

    private static Matrix3f normalOf(Matrix4f matrix) {
        try {
            return new Matrix3f(matrix).invert().transpose();
        } catch (RuntimeException singular) {
            return new Matrix3f();
        }
    }

    public Identifier texture() {
        return texture;
    }

    public int boneCount() {
        return bones.size();
    }

    public void render(PoseStack.Pose pose, VertexConsumer buffer, int light) {
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int i = 0; i < bones.size(); i++) {
            Matrix4f matrix = boneMatrices[i];
            if (matrix == null) {
                continue;
            }
            Matrix3f normalMatrix = normalMatrices[i];
            for (RawYsmModel.RawCube cube : bones.get(i).cubes) {
                if (cube.faces == null) {
                    continue;
                }
                for (RawYsmModel.RawFace face : cube.faces) {
                    if (face.positions == null || face.u == null || face.v == null) {
                        continue;
                    }
                    float[] faceNormal = face.normal == null ? new float[]{0.0F, 1.0F, 0.0F} : face.normal;
                    normal.set(faceNormal[0], faceNormal[1], faceNormal[2]).mul(normalMatrix);
                    if (normal.lengthSquared() > 0.0F) {
                        normal.normalize();
                    }
                    for (int vertex = 0; vertex < 4; vertex++) {
                        float[] raw = face.positions[vertex];
                        position.set(raw[0], raw[1], raw[2]);
                        matrix.transformPosition(position);
                        buffer.addVertex(pose, position.x, position.y, position.z)
                                .setColor(255, 255, 255, 255)
                                .setUv(face.u[vertex], face.v[vertex])
                                .setOverlay(OverlayTexture.NO_OVERLAY)
                                .setLight(light)
                                .setNormal(pose, normal.x, normal.y, normal.z);
                    }
                }
            }
        }
    }
}
