#!/usr/bin/env python3
"""Pretends to be the phone, to check the Linux host keeps the wire contract the apps rely on.

  python3 fake_phone.py PORT          (the host prints its port when it starts)

Every reply must carry a command and the request's callID, face packets must keep coming (at least once a
second), and look_at_screen must return text and a screenshot, and every question gets an answer.
"""
import json
import socket
import sys
import time
import uuid


def main(port):
    sock = socket.create_connection(("127.0.0.1", port), timeout=30)
    reader = sock.makefile("r", encoding="utf-8")

    def send(packet):
        sock.sendall((json.dumps(packet) + "\n").encode())

    def request(packet, expect):
        call = str(uuid.uuid4())
        send({**packet, "callID": call})
        faces = 0
        while True:
            reply = json.loads(reader.readline())
            if "face" in reply:
                faces += 1
                continue
            assert reply.get("callID") == call, f"reply for another call: {reply}"
            assert reply.get("command") == expect, f"expected {expect}: {str(reply)[:200]}"
            return reply, faces

    send({"hello": "fake phone"})
    first = json.loads(reader.readline())
    assert first.get("hello"), f"host should say hello first: {first}"

    # Faces keep arriving while nothing else happens.
    start, faces = time.monotonic(), 0
    while time.monotonic() - start < 2.5:
        packet = json.loads(reader.readline())
        if "face" in packet:
            f = packet["face"]
            assert set(f) == {"gazeX", "gazeY", "mood", "talk"} and -1 <= f["gazeX"] <= 1, f
            faces += 1
    assert faces >= 2, f"only {faces} face packets in 2.5 s"

    reply, _ = request({"command": "tool", "tool": "look_at_screen", "text": "{}"}, "toolResult")
    assert "Text on screen" in reply["text"] and reply.get("image"), reply.get("text", "")[:200]
    print("look_at_screen:", reply["text"].count("\nL"), "lines,", len(reply["image"]) // 1024, "KB screenshot")

    reply, _ = request({"command": "tool", "tool": "point_at_spot", "text": '{"x": 500, "y": 500}'}, "toolResult")
    assert reply["text"] == "Pointing there.", reply

    reply, _ = request({"command": "tool", "tool": "no_such_tool", "text": "{}"}, "toolResult")
    assert "Unknown tool" in reply["text"], reply

    session = {"id": "FAKE0000-TEST", "started": time.time() - 60, "ended": time.time(), "transcribing": 0,
               "entries": [{"kind": "asked", "text": "what's this?", "time": time.time() - 30},
                           {"kind": "reply", "text": "A test, mate.", "time": time.time() - 29}]}
    reply, _ = request({"command": "notes", "text": json.dumps(session)}, "notes")
    assert reply.get("text", "").endswith("fake0000-test"[:8]), reply
    print("notes written to", reply["text"])

    # A question must always get an answer, even when Claude can't be reached (then it says why).
    send({"command": "heard", "text": "we're looking at the sales numbers"})
    reply, _ = request({"command": "ask", "text": "say hi in three words"}, "answer")
    assert reply.get("text"), reply
    print("ask:", reply["text"])
    print("OK: all checks passed")


if __name__ == "__main__":
    main(int(sys.argv[1]))
