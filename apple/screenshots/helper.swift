// What capture.sh can't do with command line tools alone. Run with `xcrun swift`.
//
//   helper.swift window <pid>    the ID of the process's frontmost window, for screencapture -l
//   helper.swift screen          the main screen's frame in points, "x y width height"
//   helper.swift blank <png>     exits 0 if the image is all one colour, e.g. a display that is off

import AppKit
import CoreGraphics

func fail(_ message: String) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(2)
}

let arguments = CommandLine.arguments.dropFirst()
switch arguments.first {
case "window":
    guard let pid = arguments.dropFirst().first.flatMap(Int32.init) else { fail("usage: window <pid>") }
    let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] ?? []
    guard let window = windows.first(where: {
        $0[kCGWindowOwnerPID as String] as? Int32 == pid && $0[kCGWindowLayer as String] as? Int == 0
    }) else { exit(1) }
    print(window[kCGWindowNumber as String]!)

case "screen":
    guard let frame = NSScreen.main?.frame else { fail("no screen") }
    print(Int(frame.minX), Int(frame.minY), Int(frame.width), Int(frame.height))

case "blank":
    guard let path = arguments.dropFirst().first,
          let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { fail("usage: blank <png>") }
    // Draw it small and compare the pixels; a blank display gives a single colour.
    let size = 64
    var pixels = [UInt32](repeating: 0, count: size * size)
    let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
        guard let context = CGContext(data: buffer.baseAddress, width: size, height: size, bitsPerComponent: 8,
                                      bytesPerRow: size * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
        context.draw(image, in: CGRect(x: 0, y: 0, width: size, height: size))
        return true
    }
    guard drawn else { fail("can't read \(path)") }
    exit(Set(pixels).count <= 1 ? 0 : 1)

default:
    fail("usage: helper.swift window <pid> | screen | blank <png>")
}
