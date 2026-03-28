.PHONY: all run jar clean

all:
	mkdir -p build
	javac -d build src/*.java

run: all
	java -cp build Dara

jar: all
	echo "Main-Class: Dara" > manifest.txt
	jar cfm dara.jar manifest.txt -C build .
	rm manifest.txt
	@echo "Executavel criado: dara.jar  (java -jar dara.jar)"

clean:
	rm -rf build dara.jar
