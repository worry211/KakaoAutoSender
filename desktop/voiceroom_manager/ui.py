from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable
import re
import xml.etree.ElementTree as ET


@dataclass
class UiNode:
    text: str
    desc: str
    resource_id: str
    clickable: bool
    bounds: tuple[int, int, int, int]
    element: ET.Element


class UiTree:
    def __init__(self, xml_text: str):
        self.root = ET.fromstring(xml_text)
        self.parent = {child: parent for parent in self.root.iter() for child in parent}
        self.nodes = [self._node(el) for el in self.root.iter("node")]

    def _node(self, el: ET.Element) -> UiNode:
        a = el.attrib
        return UiNode(
            text=(a.get("text") or "").strip(),
            desc=(a.get("content-desc") or "").strip(),
            resource_id=(a.get("resource-id") or "").strip(),
            clickable=(a.get("clickable") == "true"),
            bounds=parse_bounds(a.get("bounds") or ""),
            element=el,
        )

    def find(self, terms: Iterable[str], exact: bool = False) -> list[UiNode]:
        normalized = [str(x).strip() for x in terms if str(x).strip()]
        found: list[UiNode] = []
        for node in self.nodes:
            values = (node.text, node.desc)
            for term in normalized:
                if exact:
                    match = any(value == term for value in values)
                else:
                    match = any(term in value for value in values if value)
                if match:
                    found.append(node)
                    break
        return found

    def best_click_target(self, node: UiNode) -> UiNode:
        current = node.element
        while current is not None:
            wrapped = self._node(current)
            if wrapped.clickable and valid_bounds(wrapped.bounds):
                return wrapped
            current = self.parent.get(current)
        return node

    def has_any(self, terms: Iterable[str], exact: bool = False) -> bool:
        return bool(self.find(terms, exact=exact))


def parse_bounds(value: str) -> tuple[int, int, int, int]:
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", value)
    if not m:
        return (0, 0, 0, 0)
    return tuple(int(m.group(i)) for i in range(1, 5))


def valid_bounds(bounds: tuple[int, int, int, int]) -> bool:
    x1, y1, x2, y2 = bounds
    return x2 > x1 and y2 > y1


def center(bounds: tuple[int, int, int, int]) -> tuple[int, int]:
    x1, y1, x2, y2 = bounds
    return ((x1 + x2) // 2, (y1 + y2) // 2)
