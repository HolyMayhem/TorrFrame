import Foundation
import AVFoundation
import CoreVideo
let output = URL(fileURLWithPath: CommandLine.arguments[1])
try? FileManager.default.removeItem(at: output)
let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey:320, AVVideoHeightKey:180, AVVideoCompressionPropertiesKey:[AVVideoAverageBitRateKey: 250000]])
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes:[kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32ARGB,kCVPixelBufferWidthKey as String:320,kCVPixelBufferHeightKey as String:180])
writer.add(input); writer.startWriting(); writer.startSession(atSourceTime: .zero)
for frame in 0..<900 {
 while !input.isReadyForMoreMediaData { usleep(1000) }
 var pixel: CVPixelBuffer?; CVPixelBufferPoolCreatePixelBuffer(nil,adaptor.pixelBufferPool!,&pixel)
 CVPixelBufferLockBaseAddress(pixel!,[])
 let pointer = CVPixelBufferGetBaseAddress(pixel!)!.assumingMemoryBound(to: UInt8.self)
 let stride = CVPixelBufferGetBytesPerRow(pixel!)
 for y in 0..<180 { for x in 0..<320 { let i=y*stride+x*4; pointer[i]=255; pointer[i+1]=UInt8((x+frame)%256);pointer[i+2]=UInt8(y%256);pointer[i+3]=UInt8(frame%256) } }
 CVPixelBufferUnlockBaseAddress(pixel!,[])
 guard adaptor.append(pixel!,withPresentationTime:CMTime(value:Int64(frame),timescale:30)) else { fatalError("append failed") }
}
input.markAsFinished();let semaphore=DispatchSemaphore(value:0)
writer.finishWriting { semaphore.signal() };semaphore.wait()
guard writer.status == .completed else { fatalError("encode failed") }
print("Generated original 30-second H.264 fixture")
