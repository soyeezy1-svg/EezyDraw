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
def linear_model(angle, scale, dimensions, axes):
    cx, sx, cy, sy, cz, sz = (math.cos(axes[0]), math.sin(axes[0]),
                              math.cos(axes[1]), math.sin(axes[1]),
                              math.cos(axes[2]), math.sin(axes[2]))
    basis = [[cy*cz,-cy*sz,sy], [cx*sz+sx*sy*cz,cx*cz-sx*sy*sz,-sx*cy],
             [sx*sz-cx*sy*cz,sx*cz+cx*sy*sz,cx*cy]]
    oriented = [[sum(basis[r][k]*dimensions[k]*scale*basis[c][k] for k in range(3))
                 for c in range(3)] for r in range(3)]
    co, si = math.cos(angle), math.sin(angle)
    return [[co*oriented[0][c]-si*oriented[1][c] for c in range(3)],
            [si*oriented[0][c]+co*oriented[1][c] for c in range(3)], oriented[2]]

def transformed(point, translation, linear):
    relative = [point[i] - pivot[i] for i in range(3)]
    return tuple(pivot[i]+translation[i]+sum(linear[i][j]*relative[j] for j in range(3)) for i in range(3))

def selected_preview(label, translation=(0,0,0), angle=0, scale=1, dimensions=(1,1,1), axes=(0,0,0)):
    linear = linear_model(angle,scale,dimensions,axes)
    model = [linear[0][0],linear[1][0],linear[2][0],0,
             linear[0][1],linear[1][1],linear[2][1],0,
             linear[0][2],linear[1][2],linear[2][2],0, 0,0,0,1]
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
]
error=proc('glGetError', C.c_uint)()
assert error == 0, hex(error)
print('Mesa EGL/GLES2 smoke passed: production shader compile/link, VBO layout/draw, dark contrast, preserved layer color, plan elevation projection, depth occlusion, selected translation/rotation/local dimension resizing at distant origin, no GL errors.')
print('Samples:', dark_pixel,color_pixel,plan_pixel,depth_pixel)

print('Selected preview samples:', preview_samples)
