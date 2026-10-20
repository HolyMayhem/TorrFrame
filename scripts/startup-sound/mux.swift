import Foundation
import AVFoundation
import CryptoKit

// Passthrough keeps the supplied H.264 samples, color metadata and dimensions unchanged.
let original = AVURLAsset(url: URL(fileURLWithPath: CommandLine.arguments[1]))
let audio = AVURLAsset(url: URL(fileURLWithPath: CommandLine.arguments[2]))
let destination = URL(fileURLWithPath: CommandLine.arguments[3])
let composition = AVMutableComposition()
let sourceVideo = original.tracks(withMediaType: .video)[0]
let video = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)!
try video.insertTimeRange(CMTimeRange(start: .zero, duration: original.duration), of: sourceVideo, at: .zero)
video.preferredTransform = sourceVideo.preferredTransform
let sound = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
// Keep an approved shorter soundtrack intact when the owner extends the animation.
let audioDuration = CMTimeMinimum(original.duration, audio.duration)
try sound.insertTimeRange(CMTimeRange(start: .zero, duration: audioDuration), of: audio.tracks(withMediaType: .audio)[0], at: .zero)
try? FileManager.default.removeItem(at: destination)
let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough)!
exporter.outputURL = destination
exporter.outputFileType = .mp4
exporter.shouldOptimizeForNetworkUse = true
exporter.timeRange = CMTimeRange(start: .zero, duration: original.duration)
let done = DispatchSemaphore(value: 0)
exporter.exportAsynchronously { done.signal() }
done.wait()
guard exporter.status == .completed else { fatalError("Mux failed: \(String(describing: exporter.error))") }

func videoDigest(_ asset: AVAsset) throws -> (String, Int) {
    let reader = try AVAssetReader(asset: asset)
    let output = AVAssetReaderTrackOutput(track: asset.tracks(withMediaType: .video)[0], outputSettings: nil)
    reader.add(output)
    guard reader.startReading() else { fatalError("Cannot read H.264 samples") }
    var digest = SHA256(); var count = 0
    while let sample = output.copyNextSampleBuffer() {
        if CMSampleBufferGetNumSamples(sample) == 0 { continue }
        guard let block = CMSampleBufferGetDataBuffer(sample) else { fatalError("Missing H.264 sample") }
        let length = CMBlockBufferGetDataLength(block)
        var bytes = [UInt8](repeating: 0, count: length)
        guard CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: &bytes) == noErr else {
            fatalError("Cannot copy H.264 sample")
        }
        digest.update(data: Data(bytes)); count += 1
    }
    guard reader.status == .completed else { fatalError("Incomplete H.264 sample read") }
    return (digest.finalize().map { String(format: "%02x", $0) }.joined(), count)
}
let result = AVURLAsset(url: destination)
let before = try videoDigest(original), after = try videoDigest(result)
guard before == after, result.tracks(withMediaType: .audio).count == 1,
    abs(CMTimeGetSeconds(result.duration) - CMTimeGetSeconds(original.duration)) < 0.001 else {
    fatalError("Video changed or audio/duration validation failed")
}
print("Verified unchanged H.264: \(before.1) samples, SHA-256 \(before.0)")
print("Duration: \(CMTimeGetSeconds(result.duration)); audio tracks: 1")
