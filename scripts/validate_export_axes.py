"""Read the marked-box fixtures with independent codecs and verify the coordinate contract.

Run ModelExportAxisTest first. Requires ifcopenshell, trimesh, pygltflib, numpy and networkx.
"""
import pathlib
import sys

import ifcopenshell
import ifcopenshell.geom
import ifcopenshell.validate
import numpy as np
import trimesh
from pygltflib import GLTF2


def rotate(points, axis):
    points = np.asarray(points, dtype=float)
    return points[..., [0, 2, 1]] * [1, 1, -1] if axis == 'Y_UP' else points


def check_solid(mesh, low, high, axis, shift, name):
    bounds = rotate(np.array([low, high]) - shift, axis)
    expected = np.array([bounds.min(axis=0), bounds.max(axis=0)])
    np.testing.assert_allclose(mesh.bounds, expected, atol=2e-5, rtol=0, err_msg=name)
    np.testing.assert_allclose(mesh.center_mass, expected.mean(axis=0), atol=2e-5, rtol=0)
    # Positive signed volume and a marker displaced toward +X/+Y/+Z distinguish a
    # proper rotation from a reflection; dimensions alone cannot detect a mirror.
    assert mesh.is_watertight, name
    assert mesh.is_winding_consistent, name
    np.testing.assert_allclose(mesh.volume, np.prod(np.array(high) - low), atol=2e-6)
    outward = np.einsum('ij,ij->i', mesh.face_normals, mesh.triangles_center - mesh.bounds.mean(axis=0))
    assert np.all(outward > 0), (name, outward)


folder = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/eezy-axis-export')
paths = sorted(path for path in folder.iterdir() if path.suffix in ('.ifc', '.obj', '.stl', '.gltf', '.glb'))
assert len(paths) == 42, 'Run ModelExportAxisTest to generate all 42 fixtures'
for path in paths:
    fmt, axis, units, origin = path.stem.split('-')
    factor = {'M': 1, 'CM': 100, 'MM': 1000}[units]
    shift = np.array([110.5, 221, 330]) if origin == 'MOVE' else np.zeros(3)
    if fmt == 'IFC':
        model = ifcopenshell.open(str(path))
        logger = ifcopenshell.validate.json_logger()
        ifcopenshell.validate.validate(model, logger, express_rules=True)
        assert not logger.statements, logger.statements
        solids = []
        for element in model.by_type('IfcBuildingElementProxy'):
            shape = ifcopenshell.geom.create_shape(ifcopenshell.geom.settings(), element)
            solids.append(trimesh.Trimesh(
                np.array(shape.geometry.verts).reshape(-1, 3),
                np.array(shape.geometry.faces).reshape(-1, 3), process=True))
        text = path.read_text()
        assert f'upAxis={axis}; units={units}; appVersion=axis-test' in text
    else:
        scene = trimesh.load(path, force='scene', process=True)
        mesh = scene.to_geometry()
        mesh.merge_vertices()
        # glTF node scale/translation resolves to metres; OBJ and STL are unitless.
        if fmt in ['OBJ', 'STL']:
            mesh.apply_scale(1 / factor)
        solids = list(mesh.split(only_watertight=False))
        if fmt in ['GLTF', 'GLB']:
            gltf = GLTF2().load(str(path))
            extras = gltf.asset.extras
            assert extras['upAxis'] == axis and extras['sourceUnits'] == units
            assert extras['coordinateUnits'] == 'M' and extras['appVersion'] == 'axis-test'
            assert extras['originPolicy'] == ('MOVE_TO_ORIGIN' if origin == 'MOVE' else 'PROJECT')
            np.testing.assert_allclose(extras['sourceOffsetMetres'], shift)
            # Explicit normal attributes must agree with triangle winding.
            import base64
            data = gltf.binary_blob() if fmt == 'GLB' else base64.b64decode(gltf.buffers[0].uri.split(',')[1])
            for primitive_mesh in gltf.meshes:
                primitive = primitive_mesh.primitives[0]
                def attribute(index):
                    a = gltf.accessors[index]
                    v = gltf.bufferViews[a.bufferView]
                    return np.ndarray((a.count, 3), dtype='<f4', buffer=data,
                                      offset=(v.byteOffset or 0) + (a.byteOffset or 0),
                                      strides=(v.byteStride or 12, 4))
                points = attribute(primitive.attributes.POSITION).reshape(-1, 3, 3)
                normals = attribute(primitive.attributes.NORMAL).reshape(-1, 3, 3)
                cross = np.cross(points[:, 1] - points[:, 0], points[:, 2] - points[:, 0])
                cross /= np.linalg.norm(cross, axis=1)[:, None]
                np.testing.assert_allclose(normals, np.repeat(cross[:, None, :], 3, axis=1), atol=1e-6)
        else:
            text = path.read_text()
            assert axis in text and units in text and 'axis-test' in text
            # Check stored facet/vertex normals, not just normals reconstructed by a reader.
            if fmt == 'STL':
                normals = np.array([list(map(float, line.split()[2:])) for line in text.splitlines() if line.startswith('facet normal ')])
                vertices = np.array([list(map(float, line.split()[1:])) for line in text.splitlines() if line.startswith('vertex ')]).reshape(-1, 3, 3)
            else:
                normals = np.array([list(map(float, line.split()[1:])) for line in text.splitlines() if line.startswith('vn ')])
                vertices = np.array([list(map(float, line.split()[1:])) for line in text.splitlines() if line.startswith('v ')]).reshape(-1, 3, 3)
                faces = [line.split()[1:] for line in text.splitlines() if line.startswith('f ')]
                for i, face in enumerate(faces):
                    assert [int(v.split('//')[0]) for v in face] == [3*i+1, 3*i+2, 3*i+3]
                    assert all(int(v.split('//')[1]) == i+1 for v in face)
            cross = np.cross(vertices[:, 1] - vertices[:, 0], vertices[:, 2] - vertices[:, 0])
            cross /= np.linalg.norm(cross, axis=1)[:, None]
            np.testing.assert_allclose(normals, cross, atol=1e-6)
    assert len(solids) == 2, (path, len(solids))
    box, marker = sorted(solids, key=lambda m: abs(m.volume), reverse=True)
    check_solid(box, [110, 220, 330], [111, 222, 333], axis, shift, path.name + ' box')
    check_solid(marker, [110.7, 221.5, 333.2], [110.9, 221.8, 333.6], axis, shift, path.name + ' top marker')
    print(path.name, 'dimensions, top marker, handedness, normals and metadata passed')
print('All 42 independent readbacks passed')
