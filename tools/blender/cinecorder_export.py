"""
CineCorder — Blender exporter
=============================

Exports the selected meshes (and their armature) plus the active scene camera into a ``.bcam``
animation understood by the CineCorder Minecraft mod.

Installation
------------
1. Blender -> Edit -> Preferences -> Add-ons -> Install... -> select this file.
2. Enable "CineCorder Animation Exporter".
3. File -> Export -> CineCorder Animation (.bcam).

Headless (used automatically by the mod for ``.blend`` files)
--------------------------------------------------------------
    blender --background scene.blend --factory-startup --python-exit-code 1 \
        --python cinecorder_export.py -- --output out.bcam --all --step 1

Modes
-----
* Skinned meshes (armature modifier / vertex groups): every armature bone becomes a hierarchical
  node, and the mesh is split into rigid chunks per dominant bone. This is true skeletal
  animation - bones inherit their parents, each chunk follows its bone.
* Other meshes: each object becomes one node with its local bounding box, animated by its baked
  world transform.

Coordinates are converted from Blender (Z up, -Y forward) to Minecraft (Y up, +Z south).
One Blender unit equals one Minecraft block by default. The Blender world origin becomes the
projector block centre.
"""

import argparse
import json
import math
import sys

import bpy
from bpy.props import BoolProperty, FloatProperty, IntProperty, StringProperty
from bpy.types import Operator
from bpy_extras.io_utils import ExportHelper
from mathutils import Matrix, Vector

bl_info = {
    "name": "CineCorder Animation Exporter",
    "author": "dmitri",
    "version": (1, 2, 0),
    "blender": (4, 0, 0),
    "location": "File > Export > CineCorder Animation (.bcam)",
    "description": "Export animated meshes, armatures and the camera as a .bcam file",
    "category": "Import-Export",
}

# Change of basis: Minecraft (x, y, z) -> Blender (x, -z, y)
BASIS = Matrix(((1.0, 0.0, 0.0, 0.0), (0.0, 0.0, -1.0, 0.0), (0.0, 1.0, 0.0, 0.0), (0.0, 0.0, 0.0, 1.0)))
BASIS3 = BASIS.to_3x3()


def to_mc(vector):
    """Blender vector -> Minecraft vector."""
    return Vector((float(vector.x), float(vector.z), float(-vector.y)))


def mc_matrix(matrix):
    """Blender 4x4 -> Minecraft 4x4."""
    return BASIS @ matrix @ BASIS.inverted()


def trs_from_mc(matrix, scale=1.0, offset=(0.0, 0.0, 0.0), add_offset=True):
    location, rotation, size = matrix.decompose()
    euler = rotation.to_euler("XYZ")
    ox, oy, oz = offset if add_offset else (0.0, 0.0, 0.0)
    return {
        "pos": [round(location.x * scale + ox, 5),
                round(location.y * scale + oy, 5),
                round(location.z * scale + oz, 5)],
        "rot": [round(math.degrees(euler.x), 4),
                round(math.degrees(euler.y), 4),
                round(math.degrees(euler.z), 4)],
        "scale": [round(size.x, 5), round(size.y, 5), round(size.z, 5)],
    }


def base_color(obj):
    for slot in obj.material_slots:
        material = slot.material
        if material is None:
            continue
        if material.use_nodes and material.node_tree is not None:
            for node in material.node_tree.nodes:
                if node.type == "BSDF_PRINCIPLED":
                    color = node.inputs["Base Color"].default_value
                    return [float(color[0]), float(color[1]), float(color[2]), 1.0]
        color = material.diffuse_color
        return [float(color[0]), float(color[1]), float(color[2]), 1.0]
    return [0.8, 0.8, 0.8, 1.0]


def compress(keys):
    """Drops consecutive keys that repeat the previous transform."""
    if not keys:
        return []
    result = [keys[0]]
    for key in keys[1:]:
        previous = result[-1]
        changed = any(abs(key[field][i] - previous[field][i]) > 1.0e-4
                      for field in ("pos", "rot", "scale") for i in range(3))
        if changed:
            result.append(key)
    return result


def ordered_bones(armature):
    """Bones in parent-before-child order as (bone, parent_index)."""
    result = []

    def visit(bone, parent_index):
        index = len(result)
        result.append((bone, parent_index))
        for child in bone.children:
            visit(child, index)

    for bone in armature.data.bones:
        if bone.parent is None:
            visit(bone, -1)
    return result


def find_armature(obj):
    for modifier in obj.modifiers:
        if modifier.type == "ARMATURE" and modifier.object is not None:
            return modifier.object
    if obj.parent is not None and obj.parent.type == "ARMATURE":
        return obj.parent
    return None


def bone_skeleton(armature, scale, offset):
    """Creates the node skeletons for an armature (keys are filled while sampling)."""
    bones = ordered_bones(armature)
    bone_index = {bone.name: index for index, (bone, _) in enumerate(bones)}
    nodes = []

    for index, (bone, parent) in enumerate(bones):
        nodes.append({
            "name": bone.name,
            "parent": parent,
            "pivot": [0.0, 0.0, 0.0],
            "parts": [],
            "keys": [],
        })

    return {
        "armature": armature,
        "bones": bones,
        "index": bone_index,
        "nodes": nodes,
        "keys": [[] for _ in bones],
    }


def sample_armature(data, scene, time, scale, offset):
    """Appends one key per bone for the current (already evaluated) frame."""
    armature = data["armature"]
    armature_world = armature.matrix_world
    bones = data["bones"]

    # Cache the evaluated pose matrices once per frame instead of per bone/parent pair.
    poses = [armature_world @ armature.pose.bones[bone.name].matrix for bone, _ in bones]

    for index, (bone, parent) in enumerate(bones):
        pose = poses[index]
        if parent >= 0:
            local = poses[parent].inverted() @ pose
        else:
            local = pose
        key = trs_from_mc(mc_matrix(local), scale, offset, add_offset=(parent < 0))
        key["t"] = time
        data["keys"][index].append(key)


def skeletal_parts(obj, armature, bone_index, scale, scene, frame_start):
    """Splits a skinned mesh into rigid chunks, one per dominant bone."""
    scene.frame_set(frame_start)
    mesh = obj.data
    group_names = {group.index: group.name for group in obj.vertex_groups}
    buckets = {}

    for vertex in mesh.vertices:
        if not vertex.groups:
            continue
        best = max(vertex.groups, key=lambda element: element.weight)
        if best.weight < 0.25:
            continue
        name = group_names.get(best.group)
        if name is None or name not in bone_index:
            continue
        buckets.setdefault(name, []).append(vertex.index)

    armature_world = armature.matrix_world
    object_world = obj.matrix_world
    color = base_color(obj)
    result = {}

    for bone_name, indices in buckets.items():
        bone = armature.data.bones[bone_name]
        rest = mc_matrix(armature_world @ bone.matrix_local)
        inverse = rest.inverted()
        points = []
        for index in indices:
            world = object_world @ mesh.vertices[index].co
            points.append(inverse @ to_mc(world))
        if not points:
            continue
        low = [min(point[i] for point in points) * scale for i in range(3)]
        high = [max(point[i] for point in points) * scale for i in range(3)]
        result.setdefault(bone_name, []).append({
            "from": [round(value, 5) for value in low],
            "to": [round(value, 5) for value in high],
            "color": color,
        })

    return result


def object_node(obj, scale):
    corners = [Vector(corner) for corner in obj.bound_box]
    local_min = Vector((min(c.x for c in corners), min(c.y for c in corners), min(c.z for c in corners)))
    local_max = Vector((max(c.x for c in corners), max(c.y for c in corners), max(c.z for c in corners)))
    a = to_mc(local_min)
    b = to_mc(local_max)
    low = [min(a[i], b[i]) * scale for i in range(3)]
    high = [max(a[i], b[i]) * scale for i in range(3)]

    return {
        "name": obj.name,
        "parent": -1,
        "pivot": [0.0, 0.0, 0.0],
        "parts": [{
            "from": [round(value, 5) for value in low],
            "to": [round(value, 5) for value in high],
            "color": base_color(obj),
        }],
        "keys": [],
    }


def camera_key(camera, time, scale, offset):
    matrix = camera.matrix_world
    position = Vector((
        matrix.translation.x * scale + offset[0],
        matrix.translation.z * scale + offset[1],
        -matrix.translation.y * scale + offset[2],
    ))
    direction = to_mc(matrix.to_3x3() @ Vector((0.0, 0.0, -1.0)))
    direction.normalize()
    look = position + direction * 2.0
    return {
        "t": time,
        "pos": [round(value, 5) for value in position],
        "look": [round(value, 5) for value in look],
    }


def export_animation(context, filepath, use_selection=True, step=1, scene_scale=1.0,
                     offset=(0.0, 0.0, 0.0), include_camera=True, name=None):
    scene = context.scene
    frame_start = int(scene.frame_start)
    frame_end = int(scene.frame_end)
    if frame_end < frame_start:
        frame_start, frame_end = frame_end, frame_start
    fps = float(scene.render.fps) / max(1.0, float(scene.render.fps_base))

    meshes = [obj for obj in context.selected_objects if obj.type == "MESH" and not obj.hide_render]
    if not use_selection or not meshes:
        # context.view_layer.objects respects excluded collections; skipping hidden objects keeps
        # conversion fast on big .blend files that also contain props/studio geometry.
        meshes = [obj for obj in context.view_layer.objects
                  if obj.type == "MESH" and not obj.hide_render and not obj.hide_viewport
                  and len(obj.data.vertices) > 0]
    if not meshes:
        raise RuntimeError("No mesh objects to export")

    frames = list(range(frame_start, frame_end + 1, max(1, int(step))))
    if frames[-1] != frame_end:
        frames.append(frame_end)

    armature_data = {}
    for obj in meshes:
        armature = find_armature(obj)
        if armature is not None and armature.name not in armature_data:
            armature_data[armature.name] = bone_skeleton(armature, scene_scale, offset)

    # Skeletal chunks are computed once, in the rest pose.
    extra_objects = []
    for obj in meshes:
        armature = find_armature(obj)
        if armature is not None and armature.name in armature_data:
            data = armature_data[armature.name]
            parts = skeletal_parts(obj, armature, data["index"], scene_scale, scene, frame_start)
            if parts:
                for bone_name, bone_parts in parts.items():
                    data["nodes"][data["index"][bone_name]]["parts"].extend(bone_parts)
                continue
        # No usable armature/vertex groups: bake the object's own world transform.
        extra_objects.append((object_node(obj, scene_scale), obj))

    camera = scene.camera if include_camera else None
    camera_keys = []
    total = len(frames)
    last_progress = -1

    # ONE depsgraph update per frame. Sampling bones/objects/camera inside the same loop is
    # what makes conversion fast: the old implementation called frame_set once per bone per
    # frame, which is O(bones * frames) dependency graph evaluations.
    for number, frame in enumerate(frames):
        scene.frame_set(frame)
        time = round((frame - frame_start) / fps, 5)

        for data in armature_data.values():
            sample_armature(data, scene, time, scene_scale, offset)

        for node, obj in extra_objects:
            key = trs_from_mc(mc_matrix(obj.matrix_world), scene_scale, offset)
            key["t"] = time
            node["keys"].append(key)

        if camera is not None:
            camera_keys.append(camera_key(camera, time, scene_scale, offset))

        progress = number * 10 // total
        if progress != last_progress:
            last_progress = progress
            print("CINECORDER_PROGRESS %d/%d" % (number + 1, total), flush=True)

    nodes = []
    for data in armature_data.values():
        base = len(nodes)
        for index, node in enumerate(data["nodes"]):
            node["keys"] = compress(data["keys"][index])
            if node["parent"] >= 0:
                node["parent"] += base
            nodes.append(node)
    for node, _ in extra_objects:
        node["keys"] = compress(node["keys"])
        nodes.append(node)

    document = {
        "format": "cinecorder/1",
        "name": name or (bpy.path.basename(filepath).rsplit(".", 1)[0] if filepath else scene.name),
        "fps": round(fps, 4),
        "duration": round((frame_end - frame_start) / fps, 5),
        "loop": bool(scene.get("cinecorder_loop", False)),
        "nodes": nodes,
    }

    if camera is not None:
        document["camera"] = {
            "fov": float(camera.data.angle_x * 180.0 / math.pi),
            "keys": camera_keys,
        }

    scene.frame_set(frame_start)

    with open(filepath, "w", encoding="utf-8") as handle:
        json.dump(document, handle, indent=1)
        handle.write("\n")
    return len(nodes)


class CINECORDER_OT_export(Operator, ExportHelper):
    """Export the scene as a CineCorder .bcam animation"""
    bl_idname = "export_scene.cinecorder"
    bl_label = "Export CineCorder Animation"
    bl_options = {"PRESET"}

    filename_ext = ".bcam"
    filter_glob: StringProperty(default="*.bcam", options={"HIDDEN"})

    use_selection: BoolProperty(
        name="Selected objects only", default=True,
        description="Export only selected mesh objects instead of the whole scene")
    step: IntProperty(
        name="Frame step", default=1, min=1, max=30,
        description="Sample every N frames (larger = smaller file, less smooth)")
    scene_scale: FloatProperty(
        name="Scale", default=1.0, min=0.001, max=1000.0,
        description="Blender units per Minecraft block")
    offset_x: FloatProperty(name="Offset X", default=0.0)
    offset_y: FloatProperty(name="Offset Y", default=0.0)
    offset_z: FloatProperty(name="Offset Z", default=0.0)
    include_camera: BoolProperty(
        name="Export scene camera", default=True,
        description="Write the camera path so players are switched into it during playback")

    def execute(self, context):
        try:
            count = export_animation(
                context, self.filepath, self.use_selection, self.step, self.scene_scale,
                (self.offset_x, self.offset_y, self.offset_z), self.include_camera)
        except Exception as error:  # noqa: BLE001 - reported in the Blender UI
            self.report({"ERROR"}, str(error))
            return {"CANCELLED"}
        self.report({"INFO"}, "Exported %d node(s) to %s" % (count, self.filepath))
        return {"FINISHED"}


def menu_export(self, context):
    self.layout.operator(CINECORDER_OT_export.bl_idname, text="CineCorder Animation (.bcam)")


classes = (CINECORDER_OT_export,)


def register():
    for cls in classes:
        bpy.utils.register_class(cls)
    bpy.types.TOPBAR_MT_file_export.append(menu_export)


def unregister():
    bpy.types.TOPBAR_MT_file_export.remove(menu_export)
    for cls in reversed(classes):
        bpy.utils.unregister_class(cls)


def run_cli(argv):
    parser = argparse.ArgumentParser(prog="cinecorder-export")
    parser.add_argument("--output", required=True)
    parser.add_argument("--step", type=int, default=1)
    parser.add_argument("--scale", type=float, default=1.0)
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--name", default=None)
    args = parser.parse_args(argv)

    count = export_animation(bpy.context, args.output, use_selection=not args.all,
                             step=args.step, scene_scale=args.scale, name=args.name)
    print("CINECORDER_EXPORT_OK", count)


if __name__ == "__main__":
    arguments = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    if any(argument.startswith("--output") for argument in arguments):
        run_cli(arguments)
    else:
        register()
