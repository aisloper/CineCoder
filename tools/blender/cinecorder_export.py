"""
CineCorder — Blender exporter
=============================

Exports the selected mesh objects and the active scene camera of the current frame range
into a ``.bcam`` animation file understood by the CineCorder Minecraft mod.

Installation
------------
1. Open Blender -> Edit -> Preferences -> Add-ons -> Install...
2. Select this file (``cinecorder_export.py``) and enable "CineCorder Animation Exporter".
3. Use File -> Export -> CineCorder Animation (.bcam).

Notes
-----
* Every mesh object becomes one node with a single box part (its local bounding box), which
  keeps the export fast and gives the blocky Minecraft look. Split your model into several
  objects if you want several moving boxes (wheels, legs, ...).
* Transforms are baked per frame, so animation can come from keyframes, drivers, constraints
  or parenting.
* Coordinates are converted from Blender (Z up, -Y forward) to Minecraft (Y up, +Z south).
  One Blender unit equals one Minecraft block by default (see the Scale option).
* The scene origin becomes the projector block position. Place your set around the Blender
  world origin (or use the offset fields).
"""

bl_info = {
    "name": "CineCorder Animation Exporter",
    "author": "dmitri",
    "version": (1, 0, 0),
    "blender": (4, 0, 0),
    "location": "File > Export > CineCorder Animation (.bcam)",
    "description": "Export animated meshes and the camera as a .bcam file for the CineCorder mod",
    "category": "Import-Export",
}

import json
import math

import bpy
from bpy.props import BoolProperty, FloatProperty, IntProperty, StringProperty
from bpy.types import Operator
from bpy_extras.io_utils import ExportHelper
from mathutils import Matrix, Vector

# Change of basis: Minecraft (x, y, z) -> Blender (x, -z, y)
TO_BLENDER = Matrix(((1.0, 0.0, 0.0), (0.0, 0.0, -1.0), (0.0, 1.0, 0.0)))


def to_mc(vector):
    """Blender vector -> Minecraft vector."""
    return [float(vector.x), float(vector.z), float(-vector.y)]


def to_mc_rotation(matrix3):
    """Blender rotation matrix -> Minecraft rotation matrix."""
    return TO_BLENDER @ matrix3 @ TO_BLENDER.transposed()


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


def local_box(obj):
    """Local-space bounding box of an object as (min, max)."""
    corners = [Vector(corner) for corner in obj.bound_box]
    minimum = Vector((min(c.x for c in corners), min(c.y for c in corners), min(c.z for c in corners)))
    maximum = Vector((max(c.x for c in corners), max(c.y for c in corners), max(c.z for c in corners)))
    return minimum, maximum


def export_animation(context, filepath, use_selection=True, step=1, scene_scale=1.0,
                     offset=(0.0, 0.0, 0.0), include_camera=True):
    scene = context.scene
    frame_start = int(scene.frame_start)
    frame_end = int(scene.frame_end)
    if frame_end < frame_start:
        frame_start, frame_end = frame_end, frame_start
    fps = float(scene.render.fps) / max(1.0, float(scene.render.fps_base))

    objects = [obj for obj in context.selected_objects if obj.type == "MESH"]
    if not use_selection or not objects:
        objects = [obj for obj in scene.objects if obj.type == "MESH"]
    if not objects:
        raise RuntimeError("No mesh objects to export")

    camera = scene.camera if include_camera else None
    frames = list(range(frame_start, frame_end + 1, max(1, int(step))))
    if frames[-1] != frame_end:
        frames.append(frame_end)

    nodes = []
    for obj in objects:
        minimum, maximum = local_box(obj)
        position = to_mc(minimum)
        size = to_mc(maximum)
        # to_mc flips an axis; rebuild the box from the two converted corners
        low = [min(position[i], size[i]) for i in range(3)]
        high = [max(position[i], size[i]) for i in range(3)]

        keys = []
        for frame in frames:
            scene.frame_set(frame)
            matrix = obj.matrix_world
            translation = matrix.translation
            mc_translation = Vector((
                translation.x * scene_scale + offset[0],
                translation.z * scene_scale + offset[1],
                -translation.y * scene_scale + offset[2],
            ))
            euler = to_mc_rotation(matrix.to_3x3()).to_euler("XYZ")
            keys.append({
                "t": round((frame - frame_start) / fps, 5),
                "pos": round_list(mc_translation),
                "rot": [round(math.degrees(euler.x), 4),
                        round(math.degrees(euler.y), 4),
                        round(math.degrees(euler.z), 4)],
                "scale": round_list(matrix.to_scale()),
            })

        nodes.append({
            "name": obj.name,
            "parent": -1,
            "pivot": [0.0, 0.0, 0.0],
            "parts": [{
                "from": [round(v * scene_scale, 5) for v in low],
                "to": [round(v * scene_scale, 5) for v in high],
                "color": base_color(obj),
            }],
            "keys": keys,
        })

    document = {
        "format": "cinecorder/1",
        "name": bpy.path.basename(filepath).rsplit(".", 1)[0] if filepath else scene.name,
        "fps": round(fps, 4),
        "duration": round((frame_end - frame_start) / fps, 5),
        "loop": bool(scene.get("cinecorder_loop", False)),
        "nodes": nodes,
    }

    if camera is not None:
        camera_keys = []
        for frame in frames:
            scene.frame_set(frame)
            matrix = camera.matrix_world
            translation = matrix.translation
            position = Vector((
                translation.x * scene_scale + offset[0],
                translation.z * scene_scale + offset[1],
                -translation.y * scene_scale + offset[2],
            ))
            direction = to_mc_rotation(matrix.to_3x3()) @ Vector((0.0, 0.0, -1.0))
            direction.normalize()
            look = position + direction * 2.0
            camera_keys.append({
                "t": round((frame - frame_start) / fps, 5),
                "pos": round_list(position),
                "look": round_list(look),
            })
        document["camera"] = {"fov": float(camera.data.angle_x * 180.0 / math.pi), "keys": camera_keys}

    scene.frame_set(frame_start)

    with open(filepath, "w", encoding="utf-8") as handle:
        json.dump(document, handle, indent=1)
        handle.write("\n")
    return len(nodes)


def round_list(vector):
    return [round(float(v), 5) for v in vector]


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
        except Exception as error:  # noqa: BLE001 - report to the Blender UI
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


if __name__ == "__main__":
    register()
