# Quick room, wall and socket path

1. Create an Empty project. Open Draw → Room. Leave Create walls automatically on; set thickness 200 mm and height 3000 mm.
2. Aim and Place the first corner, then the opposite corner. Verify four physical walls appear on the current level. Cancel discards an unfinished room.
3. Select the room. Verify Generate walls is visible directly in its selection toolbar. Open it, choose thickness, height, material and alignment, then Create. Repeating Create must update walls rather than duplicate them.
4. Drag a numbered room corner or change its dimensions in Properties. Check that walls follow. Draw an adjacent room sharing one edge and verify the common wall is one shared object. Undo and redo the edits.
5. Open Library, search Socket and choose it. Aim near a visible wall and Place. Verify the socket is wall-hosted, its default back box appears, and save/reopen retains the room, physical walls and device.
6. In a fresh Empty project with no room/wall, choose Socket. Verify the exact missing-wall message and Open Wall tool button. Use that button, Place start and end, then a third point to chain another wall. Cancel ends the chain and leaves the two committed walls.
7. Turn Create walls automatically off in Settings, then create another room. Verify it stays ungenerated until Generate walls → Create is used. Turn the preference back on.

Automated checks add RoomWallsTest (generation, shared identity, updates, levels, material options and undo/redo) and BuildingFlowTest (room creation/direct toolbar action, chained Wall tool, and missing-wall recovery). Physical tablet/stylus behavior remains device QA.
