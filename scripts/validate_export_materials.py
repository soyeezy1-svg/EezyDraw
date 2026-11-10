"""Read PortableMaterialExportTest's coloured fixtures with independent desktop libraries.

Requires ifcopenshell, trimesh and pygltflib. Run after :core:geometry:test.
"""
import math
import pathlib
import sys
import tempfile
import zipfile

import ifcopenshell
import ifcopenshell.geom
import ifcopenshell.validate
import trimesh
from pygltflib import GLTF2

EXPECTED = {
    "Wall": ("Paint / warm white", 0xFFE8DCC0),
    "Floor": ("Floor matte", 0xFF788997),
    "Slab": ("Slab matte", 0xFF8D8095),
    "Door": ("Door matte", 0xFFAC784B),
    "Window": ("Glass blue 40%", 0x664488CC),
    "Equipment": ("Equipment matte", 0xFF416681),
    "Fitting": ("Fitting matte", 0xFF69927C),
    "Route": ("Power red", 0xFFCC4020),
    "Xray": ("Equipment matte (X-ray)", 0x47416681),
}


def rgba(argb):
    return [((argb >> shift) & 255) / 255 for shift in (16, 8, 0, 24)]


def linear(rgb):
    return [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in rgb]


def close(actual, expected, tolerance=1e-7):
    assert len(actual) == len(expected), (actual, expected)
    assert all(math.isclose(a, b, abs_tol=tolerance) for a, b in zip(actual, expected)), (actual, expected)


def validate(folder):
    assert folder.is_dir(), "Run PortableMaterialExportTest first"
    with zipfile.ZipFile(folder / "coloured.zip") as archive, tempfile.TemporaryDirectory() as scratch:
        assert set(archive.namelist()) == {"model.obj", "model.mtl"}
        assert archive.testzip() is None
        archive.extractall(scratch)
        root = pathlib.Path(scratch)
        obj = (root / "model.obj").read_text()
        assert "mtllib model.mtl" in obj and "o Hidden" not in obj
        materials = {}
        for line in (root / "model.mtl").read_text().splitlines():
            fields = line.split()
            if not fields or fields[0] == "#":
                continue
            if fields[0] == "newmtl":
                current = materials.setdefault(fields[1], {})
            elif fields[0] in ("Kd", "d"):
                current[fields[0]] = [float(v) for v in fields[1:]]
        objects = {}
        for line in obj.splitlines():
            if line.startswith("o "):
                name = line[2:]
            elif line.startswith("usemtl "):
                objects[name] = materials[line.split()[1]]
        assert set(objects) == set(EXPECTED)
        for name, (_, colour) in EXPECTED.items():
            close(objects[name]["Kd"], rgba(colour)[:3])
            close(objects[name]["d"], rgba(colour)[3:])
        scene = trimesh.load(root / "model.obj", force="scene", process=False)
        assert len(scene.geometry) == len(EXPECTED)
        assert all(mesh.visual.kind == "texture" for mesh in scene.geometry.values())
        # Independently resolved MTL names/diffuse colours, not the optional OBJ vertex-RGB extension.
        diffuse = {tuple(mesh.visual.material.diffuse[:3]) for mesh in scene.geometry.values()}
        assert diffuse == {tuple(round(c * 255) for c in rgba(argb)[:3]) for _, argb in EXPECTED.values()}
        print("OBJ + MTL ZIP: linked named materials, RGB, opacity and desktop mesh read passed")

    for suffix in ("gltf", "glb"):
        path = folder / ("coloured." + suffix)
        model = GLTF2().load(str(path))
        assert model.asset.version == "2.0"
        assert set(node.name for node in model.nodes) == set(EXPECTED)
        for node in model.nodes:
            material = model.materials[model.meshes[node.mesh].primitives[0].material]
            expected_name, colour = EXPECTED[node.name]
            assert material.name == expected_name
            values = rgba(colour)
            close(material.pbrMetallicRoughness.baseColorFactor, linear(values[:3]) + values[3:])
            assert material.alphaMode == ("BLEND" if values[3] < 1 else "OPAQUE")
            assert material.pbrMetallicRoughness.roughnessFactor == .9
            assert material.pbrMetallicRoughness.metallicFactor == 0
        assert len(trimesh.load(path, force="scene").geometry) == len(EXPECTED)
        print(suffix.upper(), "named PBR materials, linear colour, alpha and desktop mesh read passed")

    model = ifcopenshell.open(str(folder / "coloured.ifc"))
    logger = ifcopenshell.validate.json_logger()
    ifcopenshell.validate.validate(model, logger, express_rules=True)
    assert not logger.statements, logger.statements
    products = [p for p in model.by_type("IfcProduct") if p.Representation]
    assert {p.Name for p in products} == set(EXPECTED)
    for product in products:
        expected_name, colour = EXPECTED[product.Name]
        for representation in product.Representation.Representations:
            for geometry in representation.Items:
                style = geometry.StyledByItem[0].Styles[0]
                assert style.Name == expected_name
                surface = style.Styles[0]
                close(list(surface.SurfaceColour)[1:], rgba(colour)[:3])
                close([surface.Transparency], [1 - rgba(colour)[3]])
        shape = ifcopenshell.geom.create_shape(ifcopenshell.geom.settings(), product)
        assert len(shape.geometry.verts) > 0
        assert len(shape.geometry.materials) > 0
        shader = shape.geometry.materials[0]
        close([shader.diffuse.r(), shader.diffuse.g(), shader.diffuse.b()], rgba(colour)[:3], 1e-6)
        close([shader.transparency], [1 - rgba(colour)[3]], 1e-6)
    print("IFC4 schema/rules, geometry-linked surface styles and reader colour/alpha passed")

    stl = trimesh.load(folder / "coloured.stl", process=False)
    assert len(stl.faces) > 0
    assert not stl.is_empty
    print("STL colourless geometry read passed")


if __name__ == "__main__":
    validate(pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/eezy-coloured-export"))
