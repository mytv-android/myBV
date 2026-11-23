"""Build the optional encoder JNI against separately built LGPL FFmpeg libraries."""
from pathlib import Path
import argparse
import os
import shutil
import subprocess


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prefix',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--target',type=Path,required=True)
    parser.add_argument('--abis',nargs='+',default=['arm64-v8a','armeabi-v7a'])
    args=parser.parse_args()
    repo=Path(__file__).resolve().parents[1]
    for abi in args.abis:
        prefix=(args.prefix/abi).resolve()
        assert (prefix/'include/libavcodec/avcodec.h').is_file(), f'Build FFmpeg audio for {abi} first'
        env=os.environ.copy(); env['FFMPEG_PREFIX']=str(prefix); env['CARGO_TARGET_DIR']=str(args.target.resolve())
        subprocess.run(['cargo','ndk','--platform','23','-t',abi,'-o',str(args.output.resolve()),
                        'build','--release','--locked','--manifest-path',str(repo/'native/encode/Cargo.toml')],cwd=repo/'native/encode',env=env,check=True)
        destination=args.output/abi; destination.mkdir(parents=True,exist_ok=True)
        for library in ('avcodec','avutil','swresample'):
            source=prefix/f'lib/lib{library}_mybv.so'
            assert source.is_file(), source
            shutil.copy2(source,destination/source.name)


if __name__=='__main__': main()
