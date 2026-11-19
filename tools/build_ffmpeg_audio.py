"""Build only LGPL FFmpeg E-AC-3 + sample conversion libraries for Android.

Supply unpacked official FFmpeg 8.0.1 sources. No source download, GPL or nonfree
components are enabled by this build helper. Outputs stay under --output.
"""
from pathlib import Path
import argparse
import os
import shutil
import subprocess


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--ndk',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--abis',nargs='+',default=['arm64-v8a','armeabi-v7a'])
    parser.add_argument('--jobs',type=int,default=4)
    parser.add_argument('--bash',type=Path,help='POSIX bash executable; Windows requires Git Bash')
    args=parser.parse_args()
    source=args.source.resolve(); ndk=args.ndk.resolve(); output=args.output.resolve()
    assert (source/'configure').is_file() and (ndk/'source.properties').is_file()
    assert (source/'RELEASE').read_text().strip()=='8.0.1', 'This recipe is pinned to FFmpeg 8.0.1'
    assert args.jobs in range(1,17)
    windows=os.name=='nt'
    host='windows-x86_64' if windows else ('darwin-x86_64' if os.uname().sysname=='Darwin' else 'linux-x86_64')
    llvm=ndk/'toolchains/llvm/prebuilt'/host
    extension='.exe' if windows else ''
    shell=str(args.bash.resolve()) if args.bash else (next((str(p) for p in [Path('C:/Program Files/Git/bin/bash.exe'),Path('D:/Program Files/Git/bin/bash.exe')] if p.exists()),None) if windows else shutil.which('bash'))
    if not shell: raise SystemExit('A POSIX bash with make tools is required')
    make=ndk/f'prebuilt/{host}/bin/make{extension}' if windows else Path(shutil.which('make') or 'make')
    for abi in args.abis:
        target,arch,cpu={
            'arm64-v8a':('aarch64-linux-android23','aarch64','armv8-a'),
            'armeabi-v7a':('armv7a-linux-androideabi23','arm','armv7-a'),
        }[abi]
        prefix=output/abi; build=output/f'build-{abi}'
        prefix.mkdir(parents=True,exist_ok=True); build.mkdir(parents=True,exist_ok=True)
        command=[str(source/'configure'),f'--prefix={prefix.as_posix()}', '--target-os=android',f'--arch={arch}',f'--cpu={cpu}',
            f'--host-cc={llvm.as_posix()}/bin/clang{extension}'+(' --target=x86_64-pc-windows-msvc' if windows else ''),
            '--enable-cross-compile',f'--cc={llvm.as_posix()}/bin/clang{extension} --target={target}',
            f'--cxx={llvm.as_posix()}/bin/clang++{extension} --target={target}',
            f'--ar={llvm.as_posix()}/bin/llvm-ar{extension}',f'--ranlib={llvm.as_posix()}/bin/llvm-ranlib{extension}',
            f'--nm={llvm.as_posix()}/bin/llvm-nm{extension}',f'--strip={llvm.as_posix()}/bin/llvm-strip{extension}',
            f'--sysroot={llvm.as_posix()}/sysroot','--disable-everything','--disable-autodetect',
            '--disable-programs','--disable-doc','--disable-debug','--disable-network','--disable-avdevice',
            '--disable-avformat','--disable-avfilter','--disable-swscale','--enable-avcodec','--enable-avutil','--enable-swresample',
            '--enable-encoder=eac3','--enable-shared','--disable-static','--disable-gpl','--disable-nonfree',
            '--disable-symver','--build-suffix=_mybv','--extra-cflags=-fPIC -O3',
            '--extra-ldflags=-Wl,-z,max-page-size=16384']
        # Bash receives a shell-quoted command, never interpolated model/user content.
        import shlex
        script='set -e\n'+' '.join(shlex.quote(part) for part in command)+'\n'
        if windows:
            # The NDK make is native Windows, while configure reports MSYS /c/... paths.
            # Rewrite only generated makefile paths inside this build directory.
            replacements=[]
            for directory in (source,build,prefix):
                native=directory.as_posix()
                if len(native)>2 and native[1]==':': replacements.append(('/'+native[0].lower()+native[2:],native))
            for generated in ('Makefile','ffbuild/config.mak'):
                expressions=[]
                for old,new in replacements: expressions+=['-e',f's|{old}|{new}|g']
                script+=' '.join(shlex.quote(part) for part in ['sed','-i',*expressions,generated])+'\n'
        shell_path=Path(shell).parent.parent/'usr/bin/sh.exe' if windows else Path('/bin/sh')
        make_args=[str(make),f'-j{args.jobs}',f'SHELL={shell_path.as_posix()}' if windows else 'SHELL=/bin/sh','install']
        script+=' '.join(shlex.quote(part) for part in make_args)+'\n'
        recipe=build/'build-audio.sh'; recipe.write_text(script,encoding='utf-8',newline='\n')
        env=os.environ.copy()
        if windows:
            # Capture only compiler-related vcvars settings; never print the inherited environment.
            vswhere=Path(os.environ.get('ProgramFiles(x86)','C:/Program Files (x86)'))/'Microsoft Visual Studio/Installer/vswhere.exe'
            if vswhere.is_file():
                location=subprocess.check_output([str(vswhere),'-latest','-products','*','-requires','Microsoft.VisualStudio.Component.VC.Tools.x86.x64','-property','installationPath'],text=True).strip()
                vcvars=Path(location)/'VC/Auxiliary/Build/vcvars64.bat'
                if vcvars.is_file() and not any(c in str(vcvars) for c in '&|<>%'):
                    environment_script=build/'host-env.cmd'
                    environment_script.write_text(f'@echo off\ncall "{vcvars}" >nul\nif errorlevel 1 exit /b 1\nset\n',encoding='utf-8')
                    captured=subprocess.check_output(['cmd.exe','/d','/c',str(environment_script)],text=True,errors='replace')
                    wanted={'PATH','INCLUDE','LIB','LIBPATH','VCTOOLSINSTALLDIR','WINDOWSSDKDIR','WINDOWSSDKVERSION','UCRTVERSION','UNIVERSALCRTSDKDIR'}
                    for line in captured.splitlines():
                        if '=' in line:
                            key,value=line.split('=',1)
                            if key.upper() in wanted: env[key.upper()]=value
            env['PATH']=str(Path(shell).parent.parent/'usr/bin')+os.pathsep+env['PATH']
        subprocess.run([shell,str(recipe)],cwd=build,env=env,check=True)
        # Native Windows make can truncate header install lists when expanded
        # absolute source paths exceed its command-line limit. Copy the matching
        # source/generated headers explicitly; no binaries or unrelated files.
        for library in ('libavcodec','libavutil','libswresample'):
            for origin in (source/library,build/library):
                if not origin.is_dir(): continue
                for header in origin.rglob('*.h'):
                    destination=prefix/'include'/library/header.relative_to(origin)
                    destination.parent.mkdir(parents=True,exist_ok=True)
                    shutil.copy2(header,destination)
        (prefix/'SOURCE.txt').write_text('FFmpeg 8.0.1, LGPL components only\nhttps://ffmpeg.org/releases/ffmpeg-8.0.1.tar.xz\nSHA256 05ee0b03119b45c0bdb4df654b96802e909e0a752f72e4fe3794f487229e5a41\n')
        for license_name in ('COPYING.LGPLv2.1','COPYING.LGPLv3'):
            if (source/license_name).exists(): shutil.copy2(source/license_name,prefix/license_name)


if __name__=='__main__': main()
