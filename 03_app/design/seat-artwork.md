# Seat artwork

Tool: built-in image_gen (reference-image edit, transparent background).

Source: `design/cabin_seats.png` (1536 × 1024 RGBA). `python3 -I design/cut_seats.py` cuts it into movable layers `res/drawable-nodpi/seat_*.png` (backrest/cushion per seat); the printed offsets go into `ui/Cabin3D.kt`.

The artwork replaces the former primitive cabin renderer. Compose overlays provide live seat selection and seat/window status; posture controls remain in the existing seat panel.

Final prompt:

> Use case: precise-object-edit / background-extraction.
> Asset type: transparent seat illustration for an Android automotive seat-control screen.
> Edit target: the supplied reference screenshot. Keep the realistic light-grey leather car seats, their shape, stitching, headrests, proportions, lighting, viewing angle, and arrangement: two front seats with their center console at the left, and a three-seat rear bench at the right. The near front seat is in the lower-left foreground, the far front seat is in the upper-left, and the rear bench stays on the right.
> Remove every UI element: all circular hotspots, all chevrons and arrows, all icons, every word and number, both bottom button groups, the top black line, right-side menu dots, page edges and white background. Reconstruct the upholstery cleanly wherever controls covered it. Keep the entire seats and console in frame, with a small even transparent margin and no unnecessary blank area at the bottom. Output only the clean realistic seat assembly cutout on a genuinely transparent background, preserving soft contact shadows. No text, buttons, arrows, labels, markers, logos or watermark. Do not add a car body or dashboard.
