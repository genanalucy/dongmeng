module github.com/dngmeng/cloud-api/contracttest

go 1.25.0

require (
	github.com/dngmeng/cloud-api v0.0.0
	github.com/google/uuid v1.6.0
	translator-agent v0.0.0
)

require (
	github.com/golang-jwt/jwt/v5 v5.2.1 // indirect
	golang.org/x/crypto v0.36.0 // indirect
	golang.org/x/sys v0.31.0 // indirect
)

replace github.com/dngmeng/cloud-api => ..

replace translator-agent => ../../agent
