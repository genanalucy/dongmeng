//go:build smoketest

// azure-probe v3: correct Unified Speech Protocol audio framing.
// Binary frame = [2-byte BE header length][header block][audio]; first frame
// carries a 44-byte RIFF header declaring 16k/mono/16-bit PCM.
package main

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/coder/websocket"
)

func synth(ctx context.Context, region, key string) ([]byte, error) {
	ssml := "<speak version='1.0' xml:lang='zh-CN'><voice name='zh-CN-YunxiNeural'>今天天气很好，我们一起去学校吧。</voice></speak>"
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost,
		fmt.Sprintf("https://%s.tts.speech.microsoft.com/cognitiveservices/v1", region), strings.NewReader(ssml))
	req.Header.Set("Ocp-Apim-Subscription-Key", key)
	req.Header.Set("Content-Type", "application/ssml+xml")
	req.Header.Set("X-Microsoft-OutputFormat", "raw-16khz-16bit-mono-pcm")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("tts %d: %.100s", resp.StatusCode, body)
	}
	return body, nil
}

func reqID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}

func textFrame(path, body string) []byte {
	return []byte("X-RequestId:" + reqID() + "\r\nPath:" + path + "\r\nContent-Type:application/json; charset=utf-8\r\n\r\n" + body)
}

func riffHeader() []byte {
	h := make([]byte, 44)
	copy(h[0:4], "RIFF")
	binary.LittleEndian.PutUint32(h[4:8], 0xffffffff)
	copy(h[8:12], "WAVE")
	copy(h[12:16], "fmt ")
	binary.LittleEndian.PutUint32(h[16:20], 16)
	binary.LittleEndian.PutUint16(h[20:22], 1)
	binary.LittleEndian.PutUint16(h[22:24], 1)
	binary.LittleEndian.PutUint32(h[24:28], 16000)
	binary.LittleEndian.PutUint32(h[28:32], 32000)
	binary.LittleEndian.PutUint16(h[32:34], 2)
	binary.LittleEndian.PutUint16(h[34:36], 16)
	copy(h[36:40], "data")
	binary.LittleEndian.PutUint32(h[40:44], 0xffffffff)
	return h
}

func audioFrame(pcm []byte) []byte {
	header := "X-RequestId:" + reqID() + "\r\nPath:audio\r\nContent-Type:audio/x-wav\r\nX-Timestamp:" + time.Now().UTC().Format("2006-01-02T15:04:05.0000000Z") + "\r\n\r\n"
	hb := []byte(header)
	frame := make([]byte, 2+len(hb)+len(pcm))
	binary.BigEndian.PutUint16(frame[0:2], uint16(len(hb)))
	copy(frame[2:], hb)
	copy(frame[2+len(hb):], pcm)
	return frame
}

func main() {
	region := os.Getenv("AZURE_SPEECH_REGION")
	key := os.Getenv("AZURE_SPEECH_KEY")
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	pcm, err := synth(ctx, region, key)
	if err != nil {
		fmt.Println("synth failed:", err)
		return
	}
	fmt.Printf("input pcm: %d bytes\n", len(pcm))
	url := fmt.Sprintf("wss://%s.stt.speech.microsoft.com/speech/translation/cognitiveservices/v1?from=zh-CN&to=vi-VN&format=simple", region)
	conn, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{
		HTTPHeader: map[string][]string{"Ocp-Apim-Subscription-Key": {key}},
	})
	if err != nil {
		fmt.Println("dial failed:", err)
		return
	}
	defer conn.CloseNow()
	cfg := `{"context":{"system":{"name":"dngmeng-agent","version":"1.0.0"},"os":{"platform":"linux"},"device":{"manufacturer":"dngmeng"}}}`
	if err := conn.Write(ctx, websocket.MessageText, textFrame("speech.config", cfg)); err != nil {
		fmt.Println("cfg write failed:", err)
		return
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		riff := riffHeader()
		const chunk = 2560
		first := true
		sent := 0
		for offset := 0; offset < len(pcm); offset += chunk {
			end := offset + chunk
			if end > len(pcm) {
				end = len(pcm)
			}
			payload := pcm[offset:end]
			if first {
				merged := make([]byte, len(riff)+len(payload))
				copy(merged, riff)
				copy(merged[len(riff):], payload)
				payload = merged
				first = false
			}
			if err := conn.Write(ctx, websocket.MessageBinary, audioFrame(payload)); err != nil {
				fmt.Println("audio write failed:", err)
				return
			}
			sent++
			time.Sleep(40 * time.Millisecond)
		}
		fmt.Printf("all %d frames sent; waiting\n", sent)
	}()
	for {
		msgType, payload, err := conn.Read(ctx)
		if err != nil {
			fmt.Printf("read ended: %v\n", err)
			<-done
			return
		}
		if msgType == websocket.MessageText {
			fmt.Printf("TEXT <<< %.500s\n", payload)
		} else {
			fmt.Printf("BINARY <<< %d bytes\n", len(payload))
		}
	}
}
