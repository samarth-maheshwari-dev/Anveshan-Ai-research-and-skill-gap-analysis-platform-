import { mergeAttributes, Node } from "@tiptap/core";
import { ReactNodeViewRenderer } from "@tiptap/react";

import { CitationNodeView } from "./citation-chip";

/**
 * The `citation` node (TECHSPEC §9.7, DESIGN §9.11 CitationChip): an inline atom, so the cursor treats `[3]` as one
 * character and editing text around it never breaks it. It carries the claim it points to; the number and label are
 * recomputed by the server on every save — the browser never decides them.
 */
export const Citation = Node.create({
  name: "citation",
  group: "inline",
  inline: true,
  atom: true,
  selectable: true,
  draggable: false,

  addAttributes() {
    return {
      claimId: { default: null, parseHTML: (el) => el.getAttribute("data-claim-id") },
      sourceId: { default: null, parseHTML: (el) => el.getAttribute("data-source-id") },
      n: { default: null, parseHTML: (el) => Number(el.getAttribute("data-n")) || null },
      label: { default: null, parseHTML: (el) => el.getAttribute("data-label") },
    };
  },

  parseHTML() {
    return [{ tag: "span[data-citation]" }];
  },

  renderHTML({ node, HTMLAttributes }) {
    return [
      "span",
      mergeAttributes(HTMLAttributes, {
        "data-citation": "",
        "data-claim-id": node.attrs.claimId,
        "data-source-id": node.attrs.sourceId,
        "data-n": node.attrs.n,
        "data-label": node.attrs.label,
      }),
      `[${node.attrs.n ?? ""}]`,
    ];
  },

  renderText({ node }) {
    return `[${node.attrs.n ?? ""}]`;
  },

  addNodeView() {
    return ReactNodeViewRenderer(CitationNodeView, { as: "span", className: "inline" });
  },
});
