package main
import (
 "fmt"
 "os"
 "os/signal"
 "path/filepath"
 "syscall"
 "github.com/anacrolix/torrent"
)
func main() {
 if len(os.Args)!=3 {panic("usage: seed data-directory torrent-file")}
 cfg:=torrent.NewDefaultClientConfig()
 cfg.DataDir=os.Args[1];cfg.SetListenAddr("127.0.0.1:18766")
 cfg.NoDHT=true;cfg.DisableUTP=true;cfg.DisableIPv6=true;cfg.NoDefaultPortForwarding=true;cfg.Seed=true
 client,err:=torrent.NewClient(cfg);if err!=nil {panic(err)};defer client.Close()
 t,err:=client.AddTorrentFromFile(filepath.Clean(os.Args[2]));if err!=nil {panic(err)}
 t.VerifyData();t.DownloadAll()
 if !client.WaitAll() {panic("fixture verification failed")}
 fmt.Println("Original video fixture ready for encrypted BitTorrent peers")
 done:=make(chan os.Signal,1);signal.Notify(done,syscall.SIGINT,syscall.SIGTERM);<-done
}
