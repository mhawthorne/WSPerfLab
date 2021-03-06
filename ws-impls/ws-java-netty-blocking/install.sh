#!/bin/bash

sshCommand="ssh"
update=false
gitRepo="benjchristensen"

while getopts "h:s:b:r:u" opt; do
  case $opt in
    h)
	  hostname=$OPTARG
      ;;
    b)
	  backendHost=$OPTARG
      ;;
	r)
      gitRepo=$OPTARG
      ;;
    s)
      sshCommand=$OPTARG
      ;;
    u)
      update=true
      ;;
    \?)
      echo "Invalid option: -$OPTARG" >&2
      ;;
  esac
done

if [ -z "$hostname" ]; then
	echo $'\a'-h required for hostname
	echo "$0 -h [HOSTNAME] -s [SSH COMMAND (optional)] -b [backend host] -r [Github repo username (optional: defaults to 'benjchristensen')] -u (to update only)"
	exit
fi

if [ -z "$backendHost" ]; then
	echo $'\a'-b required for backend hostname
	echo "$0 -h [HOSTNAME] -s [SSH COMMAND (optional)] -b [backend host] -r [Github repo username (optional: defaults to 'benjchristensen')] -u (to update only)"
	exit
fi

echo "Installing to host: $hostname"
echo "Backend host: $backendHost"
echo "Git repo: $gitRepo"
echo "SSH command: $sshCommand"

if $update ; then
	echo "--- Shutdown Netflix Tomcat"
	eval "$sshCommand $hostname 'sudo /etc/init.d/nflx-tomcat stop'" # need to use a different AMI so this can be removed
	echo "--- Kill all java processes"
	eval "$sshCommand $hostname 'sudo killall java'"
	echo "--- Remove previously installed ws-java-netty-blocking.zip"
	eval "$sshCommand $hostname '/bin/rm -R ws-java-netty-blocking*'"
	echo "--- Update from Git"
	eval "$sshCommand $hostname 'cd ~/WSPerfLab/; git pull'"
else
	echo "--- Shutdown Netflix Tomcat"
	eval "$sshCommand $hostname 'sudo /etc/init.d/nflx-tomcat stop'" # need to use a different AMI so this can be removed
	echo "--- Kill all java processes"
	eval "$sshCommand $hostname 'sudo killall java'"
	echo "--- Git clone WSPerfLab"
	eval "$sshCommand $hostname 'git clone git://github.com/$gitRepo/WSPerfLab.git'"
fi

echo "--- Build ws-java-netty-blocking"
eval "$sshCommand $hostname 'cd WSPerfLab/ws-impls/ws-java-netty-blocking/; ../../gradlew clean build distZip'"
echo "--- Copy distribution"
eval "$sshCommand $hostname 'cp WSPerfLab/ws-impls/ws-java-netty-blocking/build/distributions/ws-java-netty-blocking-*-SNAPSHOT.zip ~/ && cd ~; unzip ws-java-netty-blocking-*-SNAPSHOT.zip'"
echo "--- Start Netty impl"
eval "$sshCommand $hostname 'export SERVER_PORT=8080; export BACKEND_HOST=${backendHost}; cd ws-java-netty-blocking*/bin/; nohup ./startWithLog.sh > /dev/null 2>&1 &'"