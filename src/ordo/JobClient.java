package ordo;

import java.rmi.Naming;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

import config.Project;
import formats.Format;
import formats.KVFormat;
import formats.LineFormat;
import hdfs.HdfsClient;
import hdfs.NameNode;
import map.MapReduce;

public class JobClient {

    private Format.Type inputFormat;
	private Format.Type outputFormat;
	private Format.Type resReduceFormat;
	private String inputFName;
	private String outputFName;
	private String resReduceFName;
	private int nbMaps; 
	private List<ArrayList<String>> chunkList = new ArrayList<ArrayList<String>>();
	private long jobId;

	public JobClient(Format.Type inputFormat, String inputFName) {
		this.inputFormat = inputFormat;
		this.inputFName = inputFName;
		this.outputFormat = Format.Type.KV;
		this.outputFName = inputFName + "-map";
		this.resReduceFormat = Format.Type.KV;
		this.resReduceFName = inputFName + "-resf";
	}
	
	public JobClient(String name) {
		this.inputFormat = null;
		this.inputFName = null;
		this.outputFormat = Format.Type.KV;
		this.outputFName = name+"-map";
		this.resReduceFormat = Format.Type.KV;
		this.resReduceFName = name+"-resf";
	}

	public void startJob (MapReduce mr) {

		System.out.println("Submit job ...");
		
		// Création des formats
		//Format input = new LineFormat(getInputFName());
		Format output = new KVFormat(getOutputFName());
		Format resReduce = new KVFormat(getResReduceFName());

		NameNode nm = null;
		JobManager jm = null;
		// Récupération du NameNode et JobManager
		try {
			System.out.println("Récupération du stub du NameNode");
			nm = (NameNode)Naming.lookup("//"+Project.NAMENODE+":"+Project.PORT_NAMENODE+"/NameNode");
			System.out.println("Stub du NameNode récupéré !!");
			System.out.println("Récupération du stub du JobManager");
			jm = (JobManager)Naming.lookup("//"+Project.NAMENODE+":"+Project.PORT_NAMENODE+"/JobManager");
			System.out.println("Stub du JobManager récupéré !!");
		} catch (Exception e) {
			e.printStackTrace();
		}

		//Initialisation côté JobManager
		try {
			System.out.println("Ajout du Job au JobManager...");
			long id = jm.addJob(mr, getInputFormat(), getInputFName());
			this.jobId = id;
			System.out.println("Lancement du Job...");
			jm.startJob(id);
		} catch (Exception e) {
			e.printStackTrace();
		}

		// Récupération de la liste des démons
		System.out.println("Récupération de la liste des Daemons ...");
		List<Daemon> demons = new ArrayList<Daemon>();
		List<String> demonsName = new ArrayList<String>();
		try {
			demonsName = jm.getAvalaibleDaemons();
		} catch (Exception e) {
			e.printStackTrace();
		}
		for(String serverAddress : demonsName) {
			try {
				System.out.println("On récupère le stub de : //"+serverAddress+":"+Project.PORT_DAEMON+"/DaemonImpl" );
				demons.add((Daemon)Naming.lookup("//"+serverAddress+":"+Project.PORT_DAEMON+"/DaemonImpl"));
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		System.out.println("Daemons récupérés !!");

		if (this.inputFName != null) {
			//Récupération de la liste des chunks
			try {
				System.out.println("Récupération de la liste des chunks ..."); 
				ArrayList<ArrayList<String>> chunks = nm.readFileRequest(getInputFName());
				setChunkList(chunks);
				System.out.println("Chunks récupérés !!\n");
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			// Mise à jour du nombre de maps à effectuer
			setNbMaps(getChunkList().size());
		} else {
			// Effectuer un map par démon (ex. dans le cas de QuasiMonteCarlo)
			setNbMaps(demons.size());
		}
		
		// Lancement des maps sur les démons
		System.out.println("Lancement des maps ...");
		for(int i = 0; i < getNbMaps(); i++) {
			try {
				jm.submitMap(jobId, i);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
			
			String chunk;
			Daemon d;
			Format inputTmp, outputTmp;
			if (this.inputFName != null) {
				// On définit le nom du chunk
				chunk = getInputFName().split("\\.")[0] + "-serverchunk"+ i + "." + getInputFName().split("\\.")[1];
				// On récupère le nom de la machine qui possède le chunk
				String machine = getChunkList().get(i).get(0);
				// On récupère le numéro du démon sur lequel lancer le map
				int numDaemon = demonsName.indexOf(machine);
				// On récupère le bon démon dans la liste des démons
				d = demons.get(numDaemon);
				// On change le nom des Formats pour qu'ils correspondent aux fragments
				inputTmp = new LineFormat(Project.DATA_FOLDER + chunk);
				outputTmp = new KVFormat(Project.DATA_FOLDER + chunk + "-map");
			} else {
				chunk = getOutputFName()+"-serverchunk" + i;
				d = demons.get(i);
				inputTmp = null;
				outputTmp = new KVFormat(Project.DATA_FOLDER + chunk);
			}
			
			// On change le nom des Formats pour qu'ils correspondent aux fragments
			
			// On appelle runMap sur le bon démon
			try {
				d.runMap(mr, inputTmp, outputTmp, jobId);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}
		
		System.out.println("Lancement des maps terminé !!\n");

		// Puis on attends que tous les démons aient finis leur travail
		System.out.println("Attente du callback des Daemons ...");
		try {
			int maps = jm.nbMapDone(jobId);
			while(maps<nbMaps) {
				maps = jm.nbMapDone(jobId);
			}
		} catch (RemoteException e) {
			e.printStackTrace();
		}
		System.out.println("Callbacks reçus !!\n");

		// Notifier le NameNode que tous les chunks ont été écrits
		try {
			nm.allChunkWritten(getOutputFName());
     		} catch (Exception e) {
			e.printStackTrace();
		}

		// On appelle hdfs.read pour récupérer tous les résultats des maps
		try {
			HdfsClient.HdfsRead(getOutputFName() , getOutputFName());
		} catch (Exception e) {
			e.printStackTrace();
		} 

		// On peut alors lancer le reduce
		System.out.println("Ouverture du fichier contenant la concaténation des résultats des maps");
		output.open(Format.OpenMode.R);
		System.out.println("Ouverture du fichier dans lequel les résultats du reduce doivent être écrits ");
		resReduce.open(Format.OpenMode.W);
		System.out.println("Lancement du reduce ...");
		mr.reduce(output, resReduce);
		System.out.println("Reduce terminé !!!");
		System.out.println("Fermeture des fichiers en lecture et écriture ...");
		output.close();
		resReduce.close();
		System.out.println("Job terminé !!");

	}


	public void setInputFormat(Format.Type format){
		this.inputFormat = format;
	}

	public Format.Type getInputFormat() {
		return this.inputFormat;
	}

	public void setInputFname(String fname){
		this.inputFName = fname;
	}

	public String getInputFName() {
		return this.inputFName;
	}

	public void setOutputFormat(Format.Type format){
		this.outputFormat = format;
	}

	public Format.Type getOutputFormat() {
		return this.outputFormat;
	}

	public void setOutputFname(String fname){
		this.outputFName = fname;
	}

	public String getOutputFName() {
		return this.outputFName;
	}

	public void setResReduceFormat(Format.Type format){
		this.resReduceFormat = format;
	}

	public Format.Type getResReduceFormat() {
		return this.resReduceFormat;
	}

	public void setResReduceFname(String fname){
		this.resReduceFName = fname;
	}

	public String getResReduceFName() {
		return this.resReduceFName;
	}


	public void setNbMaps(int maps){
		this.nbMaps = maps;
	}

	public int getNbMaps() {
		return this.nbMaps;
	}

	public void setChunkList(ArrayList<ArrayList<String>> chunks) {
		this.chunkList = chunks;
	}

	public List<ArrayList<String>> getChunkList() {
		return this.chunkList;
	}
}

