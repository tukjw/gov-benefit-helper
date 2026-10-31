# Proxmox VM 만들기

운영 app VM, db VM, 지표 VM을 만드는 절차다. 구조는 [결정 0037](../decisions/0037-proxmox-deploy-metrics-and-load-test.md), 선택 이유는 [`DEC-OPS(배포)`](../product/development.md#결정한-사항)에 있다. VM이 망가지면 이 문서대로 다시 만들고, app·db VM은 [배포 절차](deploy.md)의 "처음 한 번"을, 지표 VM은 [지표 절차](metrics.md)를 따른다.

명령의 `<...>`는 호스트마다 다른 값이다. 스토리지 이름(`local-lvm`)도 호스트에 맞게 바꾼다.

## 구성

```
Proxmox 호스트 (Ryzen 7 8845HS, 8코어 16스레드, RAM 32GB)
├ vmbr0 (LAN) ── 인터넷, 내 PC에서 SSH
└ vmbr1 (내부 전용, 물리 포트 없음, 10.10.0.0/24)
   ├ app VM     (VMID 101) 10.10.0.11  app, cloudflared, node-exporter
   ├ db VM      (VMID 102) 10.10.0.12  MySQL, node-exporter, mysqld-exporter
   └ metrics VM (VMID 103) 10.10.0.13  Prometheus, Grafana (LAN에서 접속)
```

| VM | vCPU | 물리 코어 | 메모리 | 디스크 |
| --- | --- | --- | --- | --- |
| app | 4 | 2 (C1·C2) | 4GB | 32GB |
| db | 2 | 1 (C3) | 4GB | 32GB |
| metrics | 2 | 1 (C4) | 4GB | 32GB |

- 코어 C0은 호스트에 남긴다. C5~C7은 부하 테스트에 쓴다.
- VM마다 물리 코어를 통째로(스레드 2개씩) 줘서, 하이퍼스레드 짝을 다른 VM과 나눠 쓰지 않게 한다.

## 1. 호스트 준비

1. BIOS의 전력 모드를 성능 모드로 둔다.
2. 코어와 스레드 번호의 짝을 확인한다. 같은 `CORE`에 속한 `CPU` 두 개가 한 물리 코어다.

   ```bash
   lscpu -e=CPU,CORE
   ```

   보통 `n`과 `n+8`이 짝이다. 이 경우 C1·C2는 `1,2,9,10`, C3은 `3,11`, C4는 `4,12`다. 아래 명령의 affinity 값은 확인한 짝으로 바꾼다.
3. VM 디스크가 ZFS 스토리지에 있으면 ARC 상한을 건다. 기본값은 RAM의 절반까지 쓴다.

   ```bash
   echo "options zfs zfs_arc_max=4294967296" > /etc/modprobe.d/zfs.conf   # 4GB
   update-initramfs -u && reboot
   ```

## 2. 내부 브리지 만들기

웹 UI의 노드 → System → Network → Create → Linux Bridge에서 다음처럼 만들고 Apply Configuration을 누른다.

- Name: `vmbr1`
- IPv4/CIDR: 비움 (호스트는 이 망에 참여하지 않는다)
- Bridge ports: 비움 (물리 포트 없음. LAN에서 이 망으로 들어올 수 없다)

## 3. 템플릿 만들기

Ubuntu 24.04 클라우드 이미지로 템플릿을 한 번 만든다.

```bash
cd /root
wget https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img
qm create 9000 --name ubuntu-2404-template --ostype l26 --memory 2048 --cores 2 --cpu host \
  --net0 virtio,bridge=vmbr0 --scsihw virtio-scsi-single --agent enabled=1 \
  --serial0 socket --vga serial0
qm set 9000 --scsi0 local-lvm:0,import-from=/root/noble-server-cloudimg-amd64.img,iothread=1,cache=none
qm set 9000 --ide2 local-lvm:cloudinit --boot order=scsi0
qm template 9000
```

- CPU type `host`: 기본값(`x86-64-v2-AES` 등)은 일부 명령어셋을 가려 JVM 성능이 떨어진다.
- 디스크는 VirtIO SCSI single + `iothread=1` + `cache=none`, NIC는 VirtIO다.

## 4. VM 만들기

내 PC의 SSH 공개 키를 호스트의 파일(예: `/root/gbh.pub`)로 옮겨 둔다.

```bash
# app VM
qm clone 9000 101 --name gbh-app --full
qm set 101 --cores 4 --memory 4096 --balloon 0 --affinity <C1·C2 스레드, 예: 1,2,9,10> \
  --net1 virtio,bridge=vmbr1 \
  --ipconfig0 ip=dhcp --ipconfig1 ip=10.10.0.11/24 \
  --ciuser <사용자> --sshkeys /root/gbh.pub --onboot 1
qm disk resize 101 scsi0 32G
qm start 101

# db VM
qm clone 9000 102 --name gbh-db --full
qm set 102 --cores 2 --memory 4096 --balloon 0 --affinity <C3 스레드, 예: 3,11> \
  --net1 virtio,bridge=vmbr1 \
  --ipconfig0 ip=dhcp --ipconfig1 ip=10.10.0.12/24 \
  --ciuser <사용자> --sshkeys /root/gbh.pub --onboot 1
qm disk resize 102 scsi0 32G
qm start 102

# 지표 VM
qm clone 9000 103 --name gbh-metrics --full
qm set 103 --cores 2 --memory 4096 --balloon 0 --affinity <C4 스레드, 예: 4,12> \
  --net1 virtio,bridge=vmbr1 \
  --ipconfig0 ip=dhcp --ipconfig1 ip=10.10.0.13/24 \
  --ciuser <사용자> --sshkeys /root/gbh.pub --onboot 1
qm disk resize 103 scsi0 32G
qm start 103
```

- `--balloon 0`: 메모리를 고정한다. 측정 중에 메모리가 회수되면 GC와 버퍼 풀이 흔들린다.
- `--onboot 1`: 호스트가 재부팅되면 VM도 켜진다. 컨테이너는 `restart: unless-stopped`로 함께 올라온다.
- LAN 주소(`ipconfig0`)는 공유기에서 DHCP 고정 할당을 해 두면 SSH 설정이 바뀌지 않는다.

## 5. VM 안 준비

세 VM에 각각 SSH로 들어가 실행한다.

```bash
sudo apt-get update && sudo apt-get install -y qemu-guest-agent
sudo systemctl enable --now qemu-guest-agent
```

Docker Engine과 Compose 플러그인은 [Docker 공식 문서의 Ubuntu 설치 절차](https://docs.docker.com/engine/install/ubuntu/)(apt 저장소 방식)로 설치하고, 사용자를 `docker` 그룹에 넣은 뒤 다시 로그인한다.

```bash
sudo usermod -aG docker $USER
```

실행 구성 디렉터리를 만든다.

```bash
# app VM
sudo mkdir -p /opt/gov-benefit/app && sudo chown $USER: /opt/gov-benefit/app
# db VM
sudo mkdir -p /opt/gov-benefit/db && sudo chown $USER: /opt/gov-benefit/db
# 지표 VM
sudo mkdir -p /opt/gov-benefit/metrics && sudo chown $USER: /opt/gov-benefit/metrics
```

## 6. 내 PC의 SSH 설정

배포 스크립트는 `govbenefit-app` 별칭으로 접속한다(`DEPLOY_HOST`로 바꿀 수 있다). `~/.ssh/config`에 더한다.

```
Host govbenefit-app
  HostName <app VM의 LAN 주소>
  User <사용자>

Host govbenefit-db
  HostName <db VM의 LAN 주소>
  User <사용자>

Host govbenefit-metrics
  HostName <지표 VM의 LAN 주소>
  User <사용자>
```

## 7. 확인

- app VM에서 db VM의 MySQL에 닿는다(db를 띄운 뒤): `nc -zv 10.10.0.12 3306`
- LAN의 다른 기기에서는 db VM의 LAN 주소로 3306에, app VM의 LAN 주소로 8080에 닿지 않는다.
- 지표 VM에서 수집 대상에 닿는다(앱 배포와 [지표 절차](metrics.md) 뒤): `nc -zv 10.10.0.11 8081`, `nc -zv 10.10.0.11 9100`, `nc -zv 10.10.0.12 9100`, `nc -zv 10.10.0.12 9104`
- LAN의 다른 기기에서는 app·db VM의 LAN 주소로 8081·9100·9104에, 지표 VM의 LAN 주소로 9090에 닿지 않는다. 지표 VM의 3000(Grafana)에는 닿는다.
