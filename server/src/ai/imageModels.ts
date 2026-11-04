/**
 * Image models take the user's photo under different input names. The model ids come from env (MODEL_IMAGE,
 * MODEL_IMAGE_EDIT); this maps each family to its inputs so changing model is a config change.
 * Chosen 2026-11-04 after `npm run bakeoff`: google/nano-banana with a photo, google/imagen-4 without.
 */
export function photoField(model: string): 'input_image' | 'image' | 'image_input' {
  if (/flux-kontext/.test(model)) return 'input_image';
  if (/qwen-image-edit|ideogram/.test(model)) return 'image';
  return 'image_input';                                   // google/nano-banana(-pro), bytedance/seedream: a list of images
}

export function imageInput(model: string, prompt: string, photo: string | null): Record<string, unknown> {
  const input: Record<string, unknown> = { prompt, aspect_ratio: '3:4' };
  if (/black-forest-labs|nano-banana/.test(model)) input.output_format = 'jpg';
  if (photo) {
    const field = photoField(model);
    input[field] = field === 'image_input' ? [photo] : photo;
  }
  return input;
}
