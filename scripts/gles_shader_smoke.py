#!/usr/bin/env python3
"""Exercise production GLES2 shaders with surfaceless Mesa (libEGL + software driver).

Run from any directory: python3 scripts/gles_shader_smoke.py
This validates shaders/buffer layout, not Android lifecycle or tablet performance.
"""
import ctypes as C
import re
import math
import os
from pathlib import Path

os.environ.setdefault('LIBGL_ALWAYS_SOFTWARE', 'true')
os.environ.setdefault('MESA_SHADER_CACHE_DISABLE', 'true')
E = C.CDLL('libEGL.so.1')
E.eglGetProcAddress.argtypes = [C.c_char_p]
E.eglGetProcAddress.restype = C.c_void_p

def proc(name, result, *args):
    address = E.eglGetProcAddress(name.encode())
    assert address, name
    return C.CFUNCTYPE(result, *args)(address)

get_display = proc('eglGetPlatformDisplayEXT', C.c_void_p, C.c_uint, C.c_void_p, C.c_void_p)
display = get_display(0x31DD, None, None)
initialize = proc('eglInitialize', C.c_uint, C.c_void_p, C.POINTER(C.c_int), C.POINTER(C.c_int))
major, minor = C.c_int(), C.c_int()
assert initialize(display, C.byref(major), C.byref(minor))
assert proc('eglBindAPI', C.c_uint, C.c_uint)(0x30A0)
attributes = (C.c_int * 17)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3025, 16, 0x3038, 0, 0)
config, count = C.c_void_p(), C.c_int()
assert proc('eglChooseConfig', C.c_uint, C.c_void_p, C.POINTER(C.c_int), C.POINTER(C.c_void_p), C.c_int, C.POINTER(C.c_int))(display, attributes, C.byref(config), 1, C.byref(count)) and count.value
surface_attrs = (C.c_int * 5)(0x3057, 64, 0x3056, 64, 0x3038)
surface = proc('eglCreatePbufferSurface', C.c_void_p, C.c_void_p, C.c_void_p, C.POINTER(C.c_int))(display, config, surface_attrs)
context_attrs = (C.c_int * 3)(0x3098, 2, 0x3038)
context = proc('eglCreateContext', C.c_void_p, C.c_void_p, C.c_void_p, C.c_void_p, C.POINTER(C.c_int))(display, config, None, context_attrs)
assert context and surface
assert proc('eglMakeCurrent', C.c_uint, C.c_void_p, C.c_void_p, C.c_void_p, C.c_void_p)(display, surface, surface, context)
create_shader = proc('glCreateShader', C.c_uint, C.c_uint)
shader_source = proc('glShaderSource', None, C.c_uint, C.c_int, C.POINTER(C.c_char_p), C.POINTER(C.c_int))
compile_shader = proc('glCompileShader', None, C.c_uint)
shader_status = proc('glGetShaderiv', None, C.c_uint, C.c_uint, C.POINTER(C.c_int))
create_program = proc('glCreateProgram', C.c_uint)
attach_shader = proc('glAttachShader', None, C.c_uint, C.c_uint)
link_program = proc('glLinkProgram', None, C.c_uint)
program_status = proc('glGetProgramiv', None, C.c_uint, C.c_uint, C.POINTER(C.c_int))
source = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/eezydraw/ui/canvas/DrawingGlRenderer.kt').read_text()
program = create_program()
for name, kind in [('VERTEX_SHADER', 0x8B31), ('FRAGMENT_SHADER', 0x8B30)]:
    body = re.search(r'const val ' + name + r' = """(.*?)"""', source, re.S).group(1).encode()
    shader = create_shader(kind)
    shader_source(shader, 1, C.byref(C.c_char_p(body)), None)
    compile_shader(shader)
    status = C.c_int()
    shader_status(shader, 0x8B81, C.byref(status))
    assert status.value == 1, name + ' compile'
    attach_shader(program, shader)
link_program(program)
status = C.c_int()
program_status(program, 0x8B82, C.byref(status))
assert status.value == 1, 'program link'
proc('glUseProgram', None, C.c_uint)(program)
proc('glViewport', None, C.c_int, C.c_int, C.c_int, C.c_int)(0, 0, 64, 64)
attribute = proc('glGetAttribLocation', C.c_int, C.c_uint, C.c_char_p)
uniform = proc('glGetUniformLocation', C.c_int, C.c_uint, C.c_char_p)
position = attribute(program, b'aPosition')
color = attribute(program, b'aColor')
for attrib in [position, color]:
    proc('glEnableVertexAttribArray', None, C.c_uint)(attrib)
buffer = C.c_uint()
proc('glGenBuffers', None, C.c_int, C.POINTER(C.c_uint))(1, C.byref(buffer))
proc('glBindBuffer', None, C.c_uint, C.c_uint)(0x8892, buffer)
vertex_pointer = proc('glVertexAttribPointer', None, C.c_uint, C.c_int, C.c_uint, C.c_uint, C.c_int, C.c_void_p)
vertex_pointer(position, 3, 0x1406, 0, 28, None)
vertex_pointer(color, 4, 0x1406, 0, 28, C.c_void_p(12))
identity = [1.,0.,0.,0., 0.,1.,0.,0., 0.,0.,1.,0., 0.,0.,0.,1.]
matrix_uniform = proc('glUniformMatrix4fv', None, C.c_int, C.c_int, C.c_uint, C.POINTER(C.c_float))
def matrix(values):
    matrix_uniform(uniform(program, b'uMatrix'), 1, 0, (C.c_float * 16)(*values))
set_dark = proc('glUniform1f', None, C.c_int, C.c_float)
set_offset = proc('glUniform3f', None, C.c_int, C.c_float, C.c_float, C.c_float)
set_offset(uniform(program, b'uOffset'), 0, 0, 0)
matrix_uniform(uniform(program, b'uModel'), 1, 0, (C.c_float * 16)(*identity))
set_dark(uniform(program, b'uSelection'), 0)
clear_color = proc('glClearColor', None, C.c_float, C.c_float, C.c_float, C.c_float)
clear = proc('glClear', None, C.c_uint)
clear_color(0,0,0,1)
def reset():
    clear(0x4000 | 0x0100)
def line(rgb, z=0):
    data = (C.c_float * 14)(-.75,-1/64,z,*rgb,1, .75,-1/64,z,*rgb,1)
    proc('glBufferData', None, C.c_uint, C.c_ssize_t, C.c_void_p, C.c_uint)(0x8892, C.sizeof(data), data, 0x88E4)
    proc('glDrawArrays', None, C.c_uint, C.c_int, C.c_int)(1, 0, 2)
def pixel():
    value = (C.c_ubyte * 4)()
    proc('glReadPixels', None, C.c_int,C.c_int,C.c_int,C.c_int,C.c_uint,C.c_uint,C.c_void_p)(32,31,1,1,0x1908,0x1401,value)
    return tuple(value)
matrix(identity)
set_dark(uniform(program, b'uDark'), 1)
reset(); line((0,0,0)); dark_pixel=pixel()
assert min(dark_pixel[:3]) > 160, dark_pixel
reset(); line((.9,.1,.1)); color_pixel=pixel()
assert color_pixel[0] > 220 and color_pixel[1] < 40, color_pixel
set_dark(uniform(program, b'uDark'), 0)
plan=identity.copy(); plan[10]=0
matrix(plan); reset(); line((0,1,0),1e10); plan_pixel=pixel()
assert plan_pixel[1] > 240, plan_pixel
matrix(identity)
proc('glEnable', None, C.c_uint)(0x0B71)
proc('glDepthFunc', None, C.c_uint)(0x0203)
reset(); line((0,0,1),.5); line((1,0,0),-.5); line((0,0,1),.5); depth_pixel=pixel()
assert depth_pixel[0] > 240 and depth_pixel[2] < 15, depth_pixel
# A single selected VBO is reused for every drag preview, around a distant project-space pivot.
proc('glDisable', None, C.c_uint)(0x0B71)
set_dark(uniform(program, b'uSelection'), 1)
local_a, local_b = (-.25, -1/64, 0), (.25, -1/64, 0)
data = (C.c_float * 14)(*local_a, 1, 0, 0, 1, *local_b, 1, 0, 0, 1)
proc('glBufferData', None, C.c_uint, C.c_ssize_t, C.c_void_p, C.c_uint)(0x8892, C.sizeof(data), data, 0x88E4)
chunk_origin = (1e9, -2e9, 7.)
pivot = (chunk_origin[0] + .125, chunk_origin[1] + .125, chunk_origin[2] + .25)
def rotation_matrix(angles):
    cx, sx, cy, sy, cz, sz = (math.cos(angles[0]), math.sin(angles[0]),
                              math.cos(angles[1]), math.sin(angles[1]),
                              math.cos(angles[2]), math.sin(angles[2]))
    return [[cy*cz,-cy*sz,sy], [cx*sz+sx*sy*cz,cx*cz-sx*sy*sz,-sx*cy],
            [sx*sz-cx*sy*cz,sx*cz+cx*sy*sz,cx*cy]]

def linear_model(angle, scale, dimensions, axes, rotation=(0,0,0)):
    basis = rotation_matrix(axes)
    oriented = [[sum(basis[r][k]*dimensions[k]*scale*basis[c][k] for k in range(3))
                 for c in range(3)] for r in range(3)]
    # A global XYZ gesture follows local oriented scaling, including legacy Z rotation.
    turn = rotation_matrix((rotation[0], rotation[1], rotation[2] + angle))
    return [[sum(turn[r][k]*oriented[k][c] for k in range(3)) for c in range(3)] for r in range(3)]

def gl_model(linear):
    return [linear[0][0],linear[1][0],linear[2][0],0,
            linear[0][1],linear[1][1],linear[2][1],0,
            linear[0][2],linear[1][2],linear[2][2],0, 0,0,0,1]

def transformed(point, translation, linear):
    relative = [point[i] - pivot[i] for i in range(3)]
    return tuple(pivot[i]+translation[i]+sum(linear[i][j]*relative[j] for j in range(3)) for i in range(3))

def selected_preview(label, translation=(0,0,0), angle=0, scale=1, dimensions=(1,1,1), axes=(0,0,0), rotation=(0,0,0)):
    linear = linear_model(angle,scale,dimensions,axes,rotation)
    model = gl_model(linear)
    matrix_uniform(uniform(program, b'uModel'), 1, 0, (C.c_float * 16)(*model))
    transformed_origin = transformed(chunk_origin, translation, linear)
    offset = [transformed_origin[i] - chunk_origin[i] for i in range(3)]
    set_offset(uniform(program, b'uOffset'), *offset)
    reset()
    proc('glDrawArrays', None, C.c_uint, C.c_int, C.c_int)(1, 0, 2)
    pixels = (C.c_ubyte * (64 * 64 * 4))()
    proc('glReadPixels', None, C.c_int,C.c_int,C.c_int,C.c_int,C.c_uint,C.c_uint,C.c_void_p)(0,0,64,64,0x1908,0x1401,pixels)
    lit = []
    for y in range(64):
        for x in range(64):
            start = (y * 64 + x) * 4
            rgb = tuple(pixels[start:start+3])
            if max(rgb) > 0:
                assert 23 <= rgb[0] <= 33 and 117 <= rgb[1] <= 127 and rgb[2] > 245, (label, rgb)
                lit.append((x,y))
    assert lit, label + ' rendered no selected pixels'
    expected = []
    for local in (local_a, local_b):
        world = tuple(chunk_origin[i] + local[i] for i in range(3))
        point = transformed(world, translation, linear)
        expected.append(tuple((point[i] - chunk_origin[i] + 1) * 32 for i in range(2)))
    actual_bounds = (min(p[0] for p in lit), min(p[1] for p in lit), max(p[0] for p in lit), max(p[1] for p in lit))
    expected_bounds = (min(p[0] for p in expected), min(p[1] for p in expected), max(p[0] for p in expected), max(p[1] for p in expected))
    assert all(abs(actual_bounds[i] - expected_bounds[i]) <= 2 for i in range(4)), (label, actual_bounds, expected_bounds)
    return label, actual_bounds
preview_samples = [
    selected_preview('translation', (.25,.25,0)),
    selected_preview('90-degree rotation', angle=math.pi/2),
    selected_preview('scale about nonzero pivot', scale=2),
    selected_preview('combined transform', (.125,.25,.25), math.pi/2, 1.5),
    selected_preview('width only', dimensions=(1.5,1,1)),
    selected_preview('depth only', dimensions=(1,1.5,1)),
    selected_preview('height only', dimensions=(1,1,1.5)),
    selected_preview('local XY corner resize', dimensions=(1.5,1.25,1), axes=(.2,.15,.4)),
    selected_preview('rotated local height', dimensions=(1,1,1.5), axes=(.4,.25,.2)),
    selected_preview('X tilt about distant pivot', rotation=(math.pi/3,0,0)),
    selected_preview('Y tilt about distant pivot', rotation=(0,-math.pi/3,0)),
    selected_preview('XYZ tilt and oriented resize', translation=(.125,.125,0),
                     rotation=(.3,-.4,.2), dimensions=(1.25,.8,1.2), axes=(.2,.15,-.3)),
]
# Exercise the actual production filled-surface shader and its packed xyz/normal/rgba VBO.
surface_program = create_program()
for name, kind in [('SURFACE_VERTEX_SHADER', 0x8B31), ('SURFACE_FRAGMENT_SHADER', 0x8B30)]:
    body = re.search(r'const val ' + name + r' = """(.*?)"""', source, re.S).group(1).encode()
    shader = create_shader(kind)
    shader_source(shader, 1, C.byref(C.c_char_p(body)), None)
    compile_shader(shader)
    status = C.c_int()
    shader_status(shader, 0x8B81, C.byref(status))
    assert status.value == 1, name + ' compile'
    attach_shader(surface_program, shader)
link_program(surface_program)
program_status(surface_program, 0x8B82, C.byref(status))
assert status.value == 1, 'surface program link'
use_program = proc('glUseProgram', None, C.c_uint)
disable_attribute = proc('glDisableVertexAttribArray', None, C.c_uint)
for attrib in [position, color]:
    disable_attribute(attrib)
surface_attributes = [attribute(surface_program, name) for name in [b'aPosition', b'aNormal', b'aColor']]
assert all(a >= 0 for a in surface_attributes)
surface_buffer = C.c_uint()
proc('glGenBuffers', None, C.c_int, C.POINTER(C.c_uint))(1, C.byref(surface_buffer))
normal_uniform = proc('glUniformMatrix3fv', None, C.c_int, C.c_int, C.c_uint, C.POINTER(C.c_float))
identity3 = [1.,0.,0., 0.,1.,0., 0.,0.,1.]
def surface_inputs(lighting=0, opacity=1, model=identity, normals=identity3):
    use_program(surface_program)
    proc('glBindBuffer', None, C.c_uint, C.c_uint)(0x8892, surface_buffer)
    for attrib in surface_attributes:
        proc('glEnableVertexAttribArray', None, C.c_uint)(attrib)
    vertex_pointer(surface_attributes[0], 3, 0x1406, 0, 40, None)
    vertex_pointer(surface_attributes[1], 3, 0x1406, 0, 40, C.c_void_p(12))
    vertex_pointer(surface_attributes[2], 4, 0x1406, 0, 40, C.c_void_p(24))
    for name, values in [(b'uMatrix', identity), (b'uModel', model)]:
        matrix_uniform(uniform(surface_program, name), 1, 0, (C.c_float * 16)(*values))
    normal_uniform(uniform(surface_program, b'uNormalMatrix'), 1, 0, (C.c_float * 9)(*normals))
    set_offset(uniform(surface_program, b'uOffset'), 0, 0, 0)
    set_offset(uniform(surface_program, b'uViewDirection'), 0, 0, 1)
    for name, value in [(b'uLighting', lighting), (b'uOpacity', opacity), (b'uSelection', 0), (b'uDark', 0)]:
        set_dark(uniform(surface_program, name), value)
def square(z=0, rgb=(.8,.4,.2), normal=(0,0,1)):
    vertices = []
    for x,y in [(-.75,-.75),(.75,-.75),(.75,.75), (-.75,-.75),(.75,.75),(-.75,.75)]:
        vertices.extend((x,y,z,*normal,*rgb,1))
    data = (C.c_float * len(vertices))(*vertices)
    proc('glBufferData', None, C.c_uint, C.c_ssize_t, C.c_void_p, C.c_uint)(0x8892,C.sizeof(data),data,0x88E4)
    proc('glDrawArrays', None, C.c_uint, C.c_int, C.c_int)(4,0,6)
proc('glDisable', None, C.c_uint)(0x0B71)
proc('glDisable', None, C.c_uint)(0x0BE2)
surface_inputs(); reset(); square(); material_pixel = pixel()
assert all(abs(material_pixel[i] - (204,102,51)[i]) <= 2 for i in range(3)), material_pixel
surface_inputs(lighting=1); reset(); square(); lit_surface_pixel = pixel()
reset(); square(normal=(0,0,-1)); unlit_surface_pixel = pixel()
assert lit_surface_pixel[0] > unlit_surface_pixel[0] + 70, (lit_surface_pixel,unlit_surface_pixel)
surface_inputs(lighting=2); reset(); square(); realistic_pixel = pixel()
assert realistic_pixel[0] > 80 and realistic_pixel != material_pixel, realistic_pixel
nonuniform = identity.copy(); nonuniform[0]=2; nonuniform[10]=.5
surface_inputs(lighting=1,model=nonuniform,normals=[.5,0,0, 0,1,0, 0,0,2])
reset(); square(normal=(1,0,1)); normal_pixel = pixel()
normal_length = math.sqrt(.5**2+2**2)
key_length = math.sqrt(.45**2+.40**2+.80**2)
illumination = .36 + .64 * ((.5*.45+2*.80)/(normal_length*key_length))
assert abs(normal_pixel[0]-204*illumination) <= 3, (normal_pixel,illumination)
# Tilting a selected surface must rotate its normal as well as its positions. Work
# in chunk-local coordinates around the same billion-metre project origin as the lines.
tilted_surface_samples = []
for label, rotation in [('X tilt', (.6,0,0)), ('Y tilt', (0,-.5,0)), ('XYZ tilt', (.3,-.4,.2))]:
    linear = rotation_matrix(rotation)
    # For a pure rotation, the inverse transpose equals the rotation itself.
    normals = [linear[r][c] for c in range(3) for r in range(3)]
    surface_inputs(lighting=1, model=gl_model(linear), normals=normals)
    origin = transformed(chunk_origin, (0,0,0), linear)
    set_offset(uniform(surface_program, b'uOffset'),
               *(origin[i]-chunk_origin[i] for i in range(3)))
    reset(); square(); tilted = pixel()
    normal = [linear[i][2] for i in range(3)]
    diffuse = max(0, (normal[0]*.45 - normal[1]*.40 + normal[2]*.80)/key_length)
    expected = 204*(.36+.64*diffuse)
    assert abs(tilted[0]-expected) <= 3, (label, tilted, expected)
    tilted_surface_samples.append((label, tilted))
surface_inputs(opacity=.28)
proc('glEnable', None, C.c_uint)(0x0BE2)
proc('glBlendFunc', None, C.c_uint, C.c_uint)(0x0302,0x0303)
clear_color(0,0,1,1); reset(); square(); transparent_pixel=pixel()
assert transparent_pixel[2] > 180 and 50 < transparent_pixel[0] < 65, transparent_pixel
# Hidden-line depth prepass: a rear line is suppressed, while a front line remains visible.
surface_inputs(); proc('glDisable',None,C.c_uint)(0x0BE2)
proc('glEnable',None,C.c_uint)(0x0B71)
proc('glDepthFunc',None,C.c_uint)(0x0203)
clear_color(0,0,0,1); reset()
proc('glColorMask',None,C.c_uint,C.c_uint,C.c_uint,C.c_uint)(0,0,0,0)
square(z=-.2)
proc('glColorMask',None,C.c_uint,C.c_uint,C.c_uint,C.c_uint)(1,1,1,1)
for attrib in surface_attributes:
    disable_attribute(attrib)
use_program(program)
proc('glBindBuffer',None,C.c_uint,C.c_uint)(0x8892,buffer)
for attrib in [position,color]:
    proc('glEnableVertexAttribArray',None,C.c_uint)(attrib)
vertex_pointer(position,3,0x1406,0,28,None)
vertex_pointer(color,4,0x1406,0,28,C.c_void_p(12))
matrix(identity)
matrix_uniform(uniform(program,b'uModel'),1,0,(C.c_float*16)(*identity))
set_offset(uniform(program,b'uOffset'),0,0,0)
set_dark(uniform(program,b'uSelection'),0)
line((1,0,0),z=.5); hidden_pixel=pixel()
assert max(hidden_pixel[:3]) == 0, hidden_pixel
line((0,1,0),z=-.5); front_pixel=pixel()
assert front_pixel[1] > 240, front_pixel
error=proc('glGetError', C.c_uint)()
assert error == 0, hex(error)
print('Mesa EGL/GLES2 smoke passed: production line/surface shaders, VBO layouts, dark contrast, material colours, plan elevation projection, hidden-line depth, lighting, transparency, inverse-transpose normals, XYZ tilt lighting and selected local resizing/XYZ rotation at distant origin, no GL errors.')
print('Samples:', dark_pixel,color_pixel,plan_pixel,depth_pixel)

print('Selected preview samples:', preview_samples)
print('Surface samples:',material_pixel,lit_surface_pixel,unlit_surface_pixel,realistic_pixel,normal_pixel,transparent_pixel,hidden_pixel,front_pixel)

print('Tilted surface samples:', tilted_surface_samples)
