import { clsx } from "clsx";
import { twMerge } from "tailwind-merge"

export function cn(...inputs) {
  return twMerge(clsx(inputs));
}

export function getLenientAttribute(element, attrName) {
    if (!element || !element.attributes) return null;
    const target = attrName.toLowerCase();
    for (let i = 0; i < element.attributes.length; i++) {
        const attr = element.attributes[i];
        const localName = attr.localName || attr.name.split(':').pop();
        if (localName.toLowerCase() === target) {
            return attr.value;
        }
    }
    return null;
}
