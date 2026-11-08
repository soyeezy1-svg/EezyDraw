"""Independent readers for ModelExportTest fixtures. Requires ifcopenshell, trimesh, pygltflib."""
import pathlib
import sys
import ifcopenshell
import ifcopenshell.geom
import ifcopenshell.validate
import trimesh
from pygltflib import GLTF2

folder = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/eezy-model-export')
assert folder.is_dir(), 'Run ModelExportTest before validating its fixtures'
for path in folder.glob('*.ifc'):
    model = ifcopenshell.open(str(path))
    assert model.schema == 'IFC4'
    logger = ifcopenshell.validate.json_logger()
    ifcopenshell.validate.validate(model, logger, express_rules=True)
    assert not logger.statements, (path.name, logger.statements)
    for element in model.by_type('IfcProduct'):
        if not element.Representation:
            continue
        shape = ifcopenshell.geom.create_shape(ifcopenshell.geom.settings(), element)
        assert len(shape.geometry.verts) > 0
    print(path.name, 'IFC4 schema, rules and geometry passed')
for suffix in ['obj', 'stl', 'glb', 'gltf']:
    for path in folder.glob('*.' + suffix):
        scene = trimesh.load(path, force='scene')
        assert len(scene.geometry) > 0
        if suffix in ['glb', 'gltf']:
            assert GLTF2().load(str(path)).asset.version == '2.0'
        print(path.name, 'read successfully')
