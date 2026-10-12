from pathlib import Path
import hashlib,sys
video=Path(sys.argv[1]).read_bytes();out=Path(sys.argv[2])
def encode(value):
    if isinstance(value,dict):return b'd'+b''.join(encode(k)+encode(value[k]) for k in sorted(value))+b'e'
    if isinstance(value,int):return b'i'+str(value).encode()+b'e'
    return str(len(value)).encode()+b':'+value
piece=65536
info={b'length':len(video),b'name':b'video.mp4',b'piece length':piece,b'pieces':b''.join(hashlib.sha1(video[i:i+piece]).digest() for i in range(0,len(video),piece))}
out.write_bytes(encode({b'announce':b'http://10.0.2.2:18765/announce',b'info':info,b'comment':b'Original generated gradient video, integration test only'}))
print('Original fixture:',hashlib.sha1(encode(info)).hexdigest())
