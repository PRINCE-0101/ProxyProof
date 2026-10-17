#!/usr/bin/env python3
"""Build a signed demo APK with the official SDK tools, without Maven/Gradle downloads.
Requires JDK 17 and Android SDK platform 35 / build-tools 35.0.0 already installed.
Output: releases/ProxyProof-demo.apk. The generated debug signing key stays in build/.
"""
import os, pathlib, subprocess, sys, zipfile, xml.etree.ElementTree as E
root=pathlib.Path(__file__).resolve().parents[1]
sdk=pathlib.Path(os.environ['ANDROID_HOME'])
jdk=pathlib.Path(os.environ['JAVA_HOME'])
bt=sdk/'build-tools'/'35.0.0'
jar=sdk/'platforms'/'android-35'/'android.jar'
if not jar.exists():
    candidates=list((sdk/'platforms'/'android-35').glob('*/android.jar'))
    if not candidates:raise SystemExit('Install Android SDK Platform 35 first.')
    jar=candidates[0]
build=root/'build'/'direct';build.mkdir(parents=True,exist_ok=True)
for name in ['classes','dex','gen']:(build/name).mkdir(exist_ok=True)
def tool(folder,name):
    if os.name=='nt':name+= '.bat' if name in ('d8','apksigner') else '.exe'
    return str(folder/name)
def run(*args):subprocess.run([str(a) for a in args],check=True)
ns='http://schemas.android.com/apk/res/android'; E.register_namespace('android',ns)
manifest=E.parse(root/'android/app/src/main/AndroidManifest.xml');m=manifest.getroot()
m.set('package','in.proxyproof.app.demo');m.set('{'+ns+'}versionCode','1');m.set('{'+ns+'}versionName','1.0.0')
u=E.Element('uses-sdk');u.set('{'+ns+'}minSdkVersion','26');u.set('{'+ns+'}targetSdkVersion','35');m.insert(0,u)
app=m.find('application');app.set('{'+ns+'}usesCleartextTraffic','true');app.set('{'+ns+'}debuggable','true');app.find('activity').set('{'+ns+'}name','in.proxyproof.app.MainActivity')
manifest.write(build/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
run(tool(bt,'aapt2'),'compile','--dir',root/'android/app/src/main/res','-o',build/'resources.zip')
run(tool(bt,'aapt2'),'link','-o',build/'base.apk','--manifest',build/'AndroidManifest.xml','-I',jar,build/'resources.zip','--java',build/'gen')
sources=sorted((root/'android/app/src/main/java').rglob('*.java'))
run(tool(jdk/'bin','javac'),'-source','8','-target','8','-bootclasspath',os.pathsep.join([str(bt/'core-lambda-stubs.jar'),str(jar)]),'-d',build/'classes',*sources)
run(tool(jdk/'bin','jar'),'cf',build/'classes.jar','-C',build/'classes','.')
run(tool(bt,'d8'),'--lib',jar,'--min-api','26','--output',build/'dex',build/'classes.jar')
with zipfile.ZipFile(build/'base.apk','a') as apk:
    for dex in (build/'dex').glob('*.dex'):apk.write(dex,dex.name)
run(tool(bt,'zipalign'),'-f','4',build/'base.apk',build/'aligned.apk')
keystore=build/'debug.keystore'
if not keystore.exists():
    run(tool(jdk/'bin','keytool'),'-genkeypair','-keystore',keystore,'-storepass','android','-keypass','android','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Android Debug,O=Android,C=US')
output=root/'releases'/'ProxyProof-demo.apk';output.parent.mkdir(exist_ok=True)
run(tool(bt,'apksigner'),'sign','--ks',keystore,'--ks-pass','pass:android','--key-pass','pass:android','--out',output,build/'aligned.apk')
run(tool(bt,'apksigner'),'verify','--verbose',output)
print('APK ready:',output)
