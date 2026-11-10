import { createCn } from "cn/config";

/** Our type-scale tokens (globals.css @theme --text-*). Registered so `cn` treats `text-caption`
 *  as a font size — otherwise it would think it's a color and drop it next to `text-primary`. */
export const FONT_SIZES = [
  "display-2xl",
  "display-lg",
  "heading-lg",
  "heading-md",
  "heading-sm",
  "body-lg",
  "body",
  "body-sm",
  "caption",
  "micro",
];

export const cn = createCn({
  extend: { classGroups: { "font-size": [{ text: FONT_SIZES }] } },
});
