package hdfs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.rmi.ConnectException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.ArrayList;

import config.Project;
import config.Project.Command;
import formats.Format;
import formats.KV;
import formats.KVFormat;
import formats.LineFormat;


/**
 * Implementation of an HdfsClient class for the HDFS service.
 * Provides static methods allowing to perform operations of HDFS : 
 * writing, reading and deleting a file.
 */
public class HdfsClient {
	private static final int bufferSize = 4096;
	private static String tagHdfsClientWrite = "-clientlocalwritechunk";
	private static String tagHdfsClientRead = "-clientlocalreadchunk";
	private static final String messageHeaderError = "#HdfsClient READ : Message header received"
			+ "is incorrect or non-existent\nExpected :\n - CMD_READ Command (Commande object)"
			+ "\n - File name (String object)\n - File extension (String object)"
			+ "\n - Chunk Number (Integer object)";
	private static final String missingChunksError = "#HdfsClient READ : Could not build"
			+ " original file : at least one chunk has not been received";
	private static final String buildingFileError = "#HdfsClient READ : Error occured while"
			+ " building original file";
	private static final String missingFileError = "#HdfsClient READ : Couldn't find file, "
			+ "deletion canceled";
	private static final String nameNodeNotBoundError = "#HdfsClient READ : NameNode is not "
			+ "bound in registry, leaving process";
	private static final String nameNodeServerDoesNotRespondError = "#HdfsClient : NameNode server "
			+ "does not respond";
	private static final String fileUnknownByNameNodeError = "#HdfsClient : specified file "
			+ "unknown to NameNode";

	/**
	 * Writes a file in HDFS.
	 * The file is split in chunks that are sent on the 
	 * servers indicated by the NameNode of the cluster.
	 * 
	 * @param fmt format of the written file
	 * @param localFSSourceFname written file
	 * @param repFactor replication factor
	 */
	public static void HdfsWrite(Format.Type fmt, String localFSSourceFname, int repFactor) {
		NameNode nameNode;
		ArrayList<String> nameNodeResponse;
		String fileName = ((localFSSourceFname.contains("."))  //Separates file name and file extension
				? localFSSourceFname.substring(0,localFSSourceFname.lastIndexOf('.')) : localFSSourceFname),
				fileExtension = localFSSourceFname.substring(fileName.length(), localFSSourceFname.length());
		fileName = ((fileName.contains("/")) //Removes path from file name
				? fileName.substring(fileName.lastIndexOf('/')) : 
					((fileName.contains("\\")) ? fileName.substring(fileName.lastIndexOf('\\')) : fileName));
		Format input, tempOutput;
		KV structure;
		Socket socket;
		ObjectOutputStream socketOutputStream;
		BufferedInputStream bis;
		byte[] buf = new byte[bufferSize];
		int nbRead, chunkCounter = 0;
		long index = 0;

		try { //Connection to NameNode
			nameNode = (NameNode) Naming.lookup("//"+Project.NAMENODE+":"+Project.PORT_NAMENODE+"/NameNode"); 
		} catch (NotBoundException e) {
			System.err.println(nameNodeNotBoundError);
			return;
		} catch (ConnectException e) {
			System.err.println(nameNodeServerDoesNotRespondError);
			return;
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}
		//Format object Instantiation
		input = instanceFormat(fmt, localFSSourceFname);
		input.open(Format.OpenMode.R);
		System.out.println(">>> [HDFSWRITE]\n"
				+ ">>> Processing file " + localFSSourceFname + "...");
		//		fileLength = (new File(localFSSourceFname).length());
		//		fileSize = fileLength % Project.CHUNK_SIZE > 0 ? (int) (fileLength / Project.CHUNK_SIZE + 1)
		//				: (int) (fileLength / Project.CHUNK_SIZE);
		//		System.out.println("FILE SIZE : "+fileSize);
		//		System.out.println("fileSize : " + fileSize);
		//Each is iteration of this loop corresponds to the treatment of a chunk
		while ((structure = input.read()) != null) {
			tempOutput = instanceFormat(fmt, fileName+tagHdfsClientWrite+chunkCounter+fileExtension);
			tempOutput.open(Format.OpenMode.W);
			tempOutput.write(structure); //Write the chunk in a local file
			index = input.getIndex();
			while ((input.getIndex() - index <= Project.CHUNK_SIZE) && (structure = input.read())!= null) { 
				tempOutput.write(structure); //While end of file has not been reached and we're under the size of a chunk
			}
			tempOutput.close();
			if (structure != null && structure.v.length() > Project.CHUNK_SIZE) 
				throw new RuntimeException("# Error HdfsWrite : Input file contains "
						+ "a structure value whose size is bigger than chunk size ("+Project.CHUNK_SIZE+")");

			try { //Send chunk to server - Socket Transmission
				nameNodeResponse = nameNode.writeChunkRequest(repFactor);
				System.out.println(nameNodeResponse.get(0));
				socket = new Socket(nameNodeResponse.get(0), Project.PORT_DATANODE);
				socketOutputStream = new ObjectOutputStream(socket.getOutputStream());
				bis = new BufferedInputStream(new FileInputStream(tempOutput.getFname()), bufferSize);
				socketOutputStream.writeObject(Command.CMD_WRITE);
				socketOutputStream.writeObject(fileName);
				socketOutputStream.writeObject(fileExtension);
				socketOutputStream.writeObject(chunkCounter);
				//socketOutputStream.writeObject(fileSize);
				socketOutputStream.writeObject(repFactor);
				for (int i = 1 ; i < repFactor ; i++) {
					socketOutputStream.writeObject(nameNodeResponse.get(i));
				}
				while((nbRead = bis.read(buf)) != -1) {
					socketOutputStream.write(buf, 0, nbRead);
				}
				socketOutputStream.close();
				socket.close();
				bis.close();
				System.out.println(">>> Chunk n°" + chunkCounter + " sent on server "
						+ Project.DATANODES[chunkCounter%Project.NUMBER_OF_DATANODE]);
				(new File(tempOutput.getFname())).delete();
			} catch (Exception e) {
				e.printStackTrace();
				return;
			}
			chunkCounter++;
		}
		input.close();
		try {
			nameNode.allChunkWriten(localFSSourceFname);
		} catch (RemoteException e) {
			e.printStackTrace();
		}
		System.out.println(">>> File " + localFSSourceFname
				+ " : process completed (" + chunkCounter + " chunks)");
	}


	/**
	 * Reads a file in HDFS.
	 * Chunks form the file are collected from servers indicated by the 
	 * NameNode of the cluster and then concatenated in a destination file.
	 * 
	 * @param hdfsFname file read
	 * @param localFSDestFname destination file (concatenation of chunks)
	 */
	public static void HdfsRead(String hdfsFname, String localFSDestFname) {
		NameNode nameNode;
		ArrayList<String> nameNodeResponse;
		String fileName = ((hdfsFname.contains(".")) 
				? hdfsFname.substring(0,hdfsFname.lastIndexOf('.')) : hdfsFname),
				fileExtension = hdfsFname.substring(fileName.length(), hdfsFname.length());
		Socket socket;
		ObjectInputStream socketInputStream; ObjectOutputStream socketOutputStream;
		BufferedInputStream bis; BufferedOutputStream bos;
		Object objectReceived; File chunkReceived;
		ArrayList<Integer> chunksReceived = new ArrayList<Integer>();
		int nbRead, chunkNumber, chunkCounter = 0;
		byte[] buf = new byte[bufferSize];

		try { //Connection to NameNode
			nameNode = (NameNode) Naming.lookup("//"+Project.NAMENODE+":"+Project.PORT_NAMENODE+"/NameNode");
			nameNodeResponse = nameNode.readFileRequest(hdfsFname);
			if (nameNodeResponse == null) {
				System.err.println(fileUnknownByNameNodeError);
				return;
			}
		} catch (NotBoundException e) {
			System.err.println(nameNodeNotBoundError);
			return;
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}
		for (String server : nameNodeResponse) {
			try {
				socket = new Socket(server, Project.PORT_DATANODE);
				socketOutputStream = new ObjectOutputStream(socket.getOutputStream());
				socketOutputStream.writeObject(Command.CMD_READ);
				socketOutputStream.writeObject(fileName);
				socketOutputStream.writeObject(fileExtension);
				socketOutputStream.writeObject(chunkCounter);
				socketInputStream = new ObjectInputStream(socket.getInputStream());
				while ((objectReceived = socketInputStream.readObject()) != null) {
					if (objectReceived instanceof Command && (Command) objectReceived == Command.CMD_READ) {
						if ((objectReceived = socketInputStream.readObject()) instanceof String 
								&& ((String) objectReceived).equals(fileName)) {
							if ((objectReceived = socketInputStream.readObject()) instanceof String 
									&& ((String) objectReceived).equals(fileExtension)) {
								if ((objectReceived = socketInputStream.readObject()) instanceof Integer) {
									chunkNumber = (int) objectReceived;
									chunksReceived.add(chunkNumber);
									bos = new BufferedOutputStream(
											new FileOutputStream(
													fileName+tagHdfsClientRead+chunkNumber+fileExtension), 
											bufferSize);
									while((nbRead = socketInputStream.read(buf)) != -1) {
										bos.write(buf, 0, nbRead);
									}
									bos.close();
									chunkCounter++;
									System.out.println(">>> Chunk received : "
											+fileName+tagHdfsClientRead+chunkNumber+fileExtension);
								} else System.err.println(messageHeaderError);
							} else System.err.println(messageHeaderError);
						} else System.err.println(messageHeaderError);
					} else System.err.println(messageHeaderError);
				}
				socket.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		System.out.println(">>> End of chunks reception "
				+ "for file " + hdfsFname + "\n"
				+ ">>> Attempting to build original file from chunks...");
		//chunkCounter variable avoids expensive calls to receivedChunks.size()
		System.out.println("chunkCounter : " + chunkCounter + "\n" + chunksReceived);
		if (!checkIntegerSequence(chunksReceived, chunkCounter)) System.err.println(missingChunksError);
		else {
			for (chunkNumber = 0 ; chunkNumber < chunkCounter; chunkNumber++) {
				try {
					bis = new BufferedInputStream(new FileInputStream(
							fileName+tagHdfsClientRead+chunkNumber+fileExtension), bufferSize);
					bos = new BufferedOutputStream(new FileOutputStream(
							localFSDestFname, (chunkNumber == 0) ? false : true), bufferSize);
					while((nbRead = bis.read(buf)) != -1) {
						bos.write(buf, 0, nbRead);
					}
					bis.close();
					bos.close();
				} catch (Exception e) {
					System.err.println(buildingFileError);
					e.printStackTrace();
					chunkNumber = chunkCounter;
				}
			}
		}
		for (int chunk : chunksReceived) { //local chunks deletion
			System.out.println(">>> Deleting " + fileName+tagHdfsClientRead+chunk+fileExtension + "...");
			if ((chunkReceived = new File(fileName+tagHdfsClientRead+chunk+fileExtension)).exists()) {
				chunkReceived.delete();
			} else System.err.println(missingFileError);
		}
	}


	/**
	 * Deletes a file in HDFS.
	 * Chunks of the file are deleted on servers 
	 * indicated by the NameNode of the cluster.
	 * 
	 * @param hdfsFname file deleted
	 */
	public static void HdfsDelete(String hdfsFname) {
		NameNode nameNode;
		ArrayList<String> nameNodeResponse;
		Socket socket;
		ObjectOutputStream socketOutputStream;
		int chunkCounter = 0;
		String fileName = ((hdfsFname.contains(".")) 
				? hdfsFname.substring(0,hdfsFname.lastIndexOf('.')) : hdfsFname);
		String fileExtension = hdfsFname.substring(fileName.length(), hdfsFname.length());
		/*
		String fileDirectoryName = ((hdfsFname.contains("/")) 
				? hdfsFname.substring(0,hdfsFname.lastIndexOf('/'))+'/' : 
					((hdfsFname.contains("\\")) ? hdfsFname.substring(0,hdfsFname.lastIndexOf('\\'))+'\\' : ""));
		File fileDirectory = new File(fileDirectoryName.equals("") ? "./" : fileDirectoryName);
		 */
		System.out.println(">>> [HDFSDELETE]\n"
				+ ">>> Deleting file " + hdfsFname + "from servers...");


		try { //Connection to NameNode
			nameNode = (NameNode) Naming.lookup("//"+Project.NAMENODE+":"+Project.PORT_NAMENODE+"/NameNode");
			nameNodeResponse = nameNode.deleteFileRequest(hdfsFname);
			if (nameNodeResponse == null) {
				System.err.println(fileUnknownByNameNodeError);
				return;
			}
		} catch (NotBoundException e) {
			System.err.println(nameNodeNotBoundError);
			return;
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}

		for (String server : nameNodeResponse) {
			try {
				socket = new Socket(server, Project.PORT_DATANODE);
				socketOutputStream = new ObjectOutputStream(socket.getOutputStream());
				socketOutputStream.writeObject(Command.CMD_DELETE);
				socketOutputStream.writeObject(fileName);
				socketOutputStream.writeObject(fileExtension);
				socketOutputStream.writeObject(chunkCounter);
				socketOutputStream.close(); 
				socket.close();
				chunkCounter++;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		System.out.println(">>> Delete command was sent to servers "
				+ "for file " + hdfsFname);
	}


	/**
	 * Instancie un objet Format à partir de son type et son nom de fichier
	 * @param fmt
	 * @param fileName
	 * @return
	 * @throws RuntimeException
	 */
	private static Format instanceFormat(Format.Type fmt, String fileName) throws RuntimeException {
		if (fmt == Format.Type.LINE) {
			return new LineFormat(fileName);
		}
		else if (fmt == Format.Type.KV) {
			return new KVFormat(fileName);
		}
		else throw new RuntimeException("instanceFormat : Unsupported input format");
	}


	/**
	 * Vérifie si une ArrayList<Integer> contient tous les entiers de 0 à listSize-1
	 * @param list la liste contenant les id de chunk (dans ce contexte)
	 * @param listSize taille de la liste
	 * @return true si la liste contient tous les entiers entre 0 et listSize-1
	 */
	static boolean checkIntegerSequence(ArrayList<Integer> list, int listSize) {
		ArrayList<Integer> listCopy = new ArrayList<Integer>(list);
		for (int i = 0 ; i < listSize ; i++) {
			if (list.contains(i)) listCopy.remove((Integer) i);
			else return false;
		}
		return true;
	}

	/**
	 * Affiche l'utilisation de l'application sur le flux de sortie
	 */
	private static void printUsage() {
		System.out.println("[HDFSCLIENT] Incorrect parameters\nUsage :"
				+ "\njava HdfsClient write <line|kv> <file>"
				+ "\njava HdfsClient read <file> <destfile>"
				+ "\njava HdfsClient delete <file>");
	}


	/**
	 * main de l'application
	 * Utilisations prévues : 
	 * java HdfsClient write <line|kv> <file>
	 * java HdfsClient read <file> <destfile>
	 * java HdfsClient delete <file>
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		try {
			if (args.length < 2 || args.length > 3) {
				printUsage();
			} else {
				switch (args[0]) {
				case "write":
					if (args.length < 3) {
						printUsage();
					}
					else if (args[1].equals("line"))
						HdfsWrite(Format.Type.LINE, args[2], 1);
					else if (args[1].equals("kv"))
						HdfsWrite(Format.Type.KV, args[2], 1);
					else {
						printUsage();
					}
					break;
				case "read":
					if (args.length < 3) {
						printUsage();
					}
					HdfsRead(args[1], args[2]);
					break;
				case "delete":
					HdfsDelete(args[1]);
					break;
				default:
					printUsage();
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
